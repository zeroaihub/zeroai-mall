# P0 冒烟清单（Agent 接入基线）

用途：Agent 改造前记录「系统本来是怎么工作的」，改造后每次回归逐条对照。
本次执行：**2026-10-24 22:49–22:54，6/6 通过**，代码基线 `p0-base`。

---

## 一、环境准备

| 组件 | 来源 | 端口 | 备注 |
|---|---|---|---|
| MySQL 8.0.33 | compose `mysql` | 3306 | 首次启动自动执行 `db-init/*.sql` |
| Elasticsearch 7.14.0 | compose `elasticsearch` | 9200 | 已挂载 ik / pinyin 插件 |
| RabbitMQ 3.12.7 | compose `rabbitmq` | 5672 / 15672 | 延迟消息插件已启用 |
| Redis | **本机 memurai** | 6379 | compose 内的 redis 未启动，端口被占用 |
| PGVector / Ollama | compose | — | P6 才用，本次不启动 |

启动命令（Docker Desktop 必须带 override 文件，原因见「坑 1」）：

```bash
docker compose -f docker-compose-es7-rabbitmq-redis-mysql.yml \
               -f docker-compose.docker-desktop.yml up -d mysql elasticsearch rabbitmq
```

### 应用

需要**两个进程**，缺一个则下单只在队列里躺着：

```bash
# 1) 入口服务（82 端口）
unset SERVER__PORT SERVER__HOST      # 见坑 5
java -jar jars/zeroai-mobile-api.jar

# 2) 消息消费者（订单落库、超时取消都由它做）
java -jar jars/zeroai-message-consumer.jar
```

打包命令：

```bash
export JAVA_HOME=<jdk17>
mvn -B -pl zeroai-mobile-api -am clean package -DskipTests
mvn -B -pl zeroai-message/zeroai-message-consumer -am clean package -DskipTests
```

---

## 二、六条冒烟（2026-10-24 实测）

约定：`TOKEN` 为登录返回的 token，通过 `Authorization: ${TOKEN}` 传递（未启用前缀）。
登录态由 sa-token 维护，正常情况无需额外 header。

### 1. 登录

```bash
# 免短信：直接写一条"已验证"的验证码（必须写 JSON 字符串 "1"，见坑 4）
python scripts/dev/set-login-code.py --mobile 13800000000 --code 1234

curl -s -X POST "http://localhost:82/login" \
  -H "Content-Type: application/json" \
  -d '{"mobile":"13800000000","yzm":"1234"}'
```

- [x] 返回 `{"code":200,"data":"<token>"}`
- [x] token 记录：`56eaa332-7089-418e-9b8c-8336ea8512c7`（本次，仅本地有效）
- 备注：手机号不存在时该接口会**自动注册**，并赠送注册优惠券，usersId=93

### 2. 首页

```bash
curl -s "http://localhost:82/home/index" -H "Authorization: ${TOKEN}"
```

- [x] 200，约 12KB，含 `bannerList` / 分类 / 商品列表，无异常堆栈

### 3. 搜索（ES 链路）

```bash
# 前置：索引必须存在，见坑 3
python scripts/dev/seed-es-goods.py
curl -s "http://localhost:82/search/result?keyword=%E6%89%8B%E6%9C%BA&pageNum=1&pageSize=10" \
  -H "Authorization: ${TOKEN}"
```

- [x] 200，返回商品列表，`id` 非空（本次 230 条商品入索引）
- [x] 索引缺失时的行为：`code=500` + `index_not_found_exception`（P3 降级设计的事实依据）

### 4. 商品详情

```bash
curl -s "http://localhost:82/goods/detail/1030003" -H "Authorization: ${TOKEN}"
```

- [x] 200，约 5KB，含 `info` / `specificationList` / `productList` / `attributes`
- 本次取值：goodsId=1030003，productId=42，库存 100

### 5. 加购

```bash
curl -s -X POST "http://localhost:82/cart" \
  -H "Content-Type: application/json" -H "Authorization: ${TOKEN}" \
  -d '{"goodsId":1030003,"productId":42,"number":1}'

curl -s "http://localhost:82/cart/list" -H "Authorization: ${TOKEN}"
```

- [x] 加购成功，购物车可见（cartId=8，userId=93，maxNum=100）

### 6. 下单

先建收货地址（新用户没有地址会失败）：

```bash
curl -s -X POST "http://localhost:82/address" \
  -H "Content-Type: application/json" -H "Authorization: ${TOKEN}" \
  -d '{"name":"冒烟测试","province":"上海市","city":"上海市","county":"浦东新区",
       "addressDetail":"测试路1号","areaCode":"310115","postalCode":"200120",
       "tel":"13800000000","isDefault":true}'

curl -s -X POST "http://localhost:82/order/submit" \
  -H "Content-Type: application/json" -H "Authorization: ${TOKEN}" \
  -d '{"cartIdArr":[8],"addressId":3,"message":"P0冒烟测试","payType":1}'
```

- [x] 返回 `{"code":200,"data":{"actualPrice":1469.00,"orderSn":"1792853543000001"}}`
- [x] `order/list` 可查，`orderStatusText=未付款`（order_status=101）
- [x] 落库延迟约 2s（异步，见坑 2）
- 订单号基线：`1792853543000001`，作为 P4 幂等测试对照（同参重复提交应只生成 1 单）

---

## 三、本次踩到的坑（换环境必看）

**坑 1 · Docker Desktop 下 host 网络不转发端口**
compose 用 `network_mode: "host"`，在 Linux 上端口直通宿主机；Docker Desktop 跑在虚拟机里，
宿主机 `localhost:3306/9200` 全部连接被拒，`docker compose` 会提示
`Published ports are discarded when using host network mode`。
解决：叠加 `docker-compose.docker-desktop.yml`（把各服务切回 bridge，让 `ports` 生效）。

**坑 2 · 下单是异步的，只起 mobile-api 不会落库**
`/order/submit` 只把消息投到 `order_direct_queue` 就返回 orderSn。必须同时启动
`zeroai-message-consumer`，否则订单表永远是 0 条、`order/list` 一直为空。

**坑 3 · ES 的 `goods` 索引不会自动创建**
商品索引导入只在 admin 后台触发。本地只跑 mobile 时，搜索直接抛 `index_not_found_exception`。
用 `scripts/dev/seed-es-goods.py` 一键建索引 + 灌数。
另注意：mapping 里 `createTime` 是 `date` 类型且默认 `strict_date_optional_time`，
写 `2018-02-01 00:00:00` 会 400，必须写成 `2018-02-01T00:00:00+08:00`。

**坑 4 · 验证码写裸 `1` 会被判错**
`RedisTemplate` 的 value 序列化器是 `GenericFastJsonRedisSerializer`。写入裸 `1` 反序列化成
Integer，`Objects.equals("1", value)` 为 false。必须写 JSON 字符串 `"1"`。

**坑 5 · `SERVER__PORT` 环境变量会把 82 顶掉**
某些宿主环境会注入 `SERVER__PORT`，Spring 的松散绑定把它当成 `server.port`。
现象：日志出现 `Tomcat initialized with port 5xxxx`、随后 `Port already in use`。
解决：启动前 `unset SERVER__PORT SERVER__HOST`。

**坑 6 · `db-init` 的脚本按字母序执行，`seckill.sql` 会先于主库跑**
MySQL 容器首次启动会按文件名顺序执行 `db-init/*.sql`，`seckill.sql` 要往 `sys_menu` 插数据，
此时主库还没导入 → `ERROR 1146` → entrypoint 直接退出，主库一条都没导进去。
首次初始化前的临时处理：先把依赖主库的 5 个脚本移出目录，只留主库脚本启动，
起来后再按 `local_message → order_status_log → payment_flow → inventory_stock → seckill` 顺序补导。
（根治办法在 SOP §7 偏差 D11 讨论：给脚本加数字前缀。）

**坑 7 · `zeroai-common` 里已有一份 dev 配置**
`zeroai-common/src/main/resources/application-dev.yml` 才是本项目的真实 dev 配置，用的是
`spring.datasource.master.jdbc-url` 多数据源结构（`HikariCpConfig` 按 `spring.datasource.master`
前缀绑定）。**不要**在 `zeroai-mobile-api/src/main/resources/` 下另建同名文件，
classpath 只加载第一个同名文件，会导致数据源配置整体丢失、报
`dataSource or dataSourceClassName or jdbcUrl is required`。

**坑 8 · ES 401：compose 里的引号进了密码**（已根治）

原 compose 写的是 `- ELASTIC_PASSWORD="123456"`。这是**列表写法**，引号不是 YAML 定界符
而是字符串的一部分，所以 ES 初始化的真实密码是 `"123456"`（含双引号），而
`application-dev.yml` 里的 `es.config.password: 123456` 登录必然 401：

```
security_exception: unable to authenticate user [elastic] for REST request [/goods/_search]
```

根治方式（2026-10-25 已做，**现在启动不需要任何 `-D` 参数**）：

1. compose 改成 `- ELASTIC_PASSWORD=123456`（去掉引号，防止删卷重建又变回去）
2. 把现有容器的密码改回来，免得删卷：
   ```bash
   curl -X POST -u 'elastic:"123456"' -H 'Content-Type: application/json' \
     -d '{"password":"123456"}' http://127.0.0.1:9200/_security/user/elastic/_password
   ```
   密码存在 `.security` 索引里（即 `./es/data` 数据卷），改完即持久化，
   **调用方无需重启**——RestClient 每次请求都带 Basic 头，配置没变、服务端认可了而已。

**坑 9 · `/upload/**` 下所有图片都 404**
`MvcConfig` 把 `/upload/**` 映射到 `zeroai.uploadDir`（默认 `E:\zeroai-mall\webp`），
但**仓库不含图片素材**（原始素材在作者私有 OSS；公开演示站已下线，git 历史也从未
提交过 upload 目录）。表现是接口数据正常、日志刷
`NoResourceFoundException: No static resource xxx.webp`、前端满屏碎图。

库里被引用的文件共 **8763** 个（商品主图 230、详情长图 7551、相册 739、分类图标 92、
分类图 92、评论晒图 33、金刚位 20、banner 2、规格图 3、头像 1），靠手工补不可能。
用 `scripts/dev/gen-placeholder-images.py` 生成：它从数据库读出全部引用，按用途给尺寸
（商品 800²、banner 750×360、金刚位 160²…），按文件名 hash 取色相，把商品名画在图上。

```bash
python scripts/dev/gen-placeholder-images.py           # 约 8700 张 / 110 MB / 多进程 2 分钟
```

> 写脚本时的坑：MySQL 的 `to_base64()` **每 76 字符插一个换行**，用 `-B` 导 TSV 时会把
> 长字段（相册 JSON、详情 HTML）切成多行、解码成全空 —— 表现是"相册和详情图一张都统计不到"。
> 改用 `hex()`（不插换行）即可。

---

## 四、记录表

| # | 检查项 | 结果 | 备注 |
|---|---|---|---|
| 1 | 登录 | 通过 | 新用户自动注册，userId=93 |
| 2 | 首页 | 通过 | 12KB |
| 3 | 搜索 | 通过 | 需先灌 ES 索引（230 条） |
| 4 | 商品详情 | 通过 | goodsId=1030003 |
| 5 | 加购 | 通过 | cartId=8 |
| 6 | 下单 | 通过 | orderSn=1792853543000001，需 consumer 在线 |

- 执行人：Agent（本地 Docker 环境）
- 执行时间：2026-10-24 22:49–22:54
- 代码基线：`p0-base`
- 全量测试：未执行（本次仅冒烟；`mvn test` 建议在 CI 或整机环境跑，属 P1 收尾项）
