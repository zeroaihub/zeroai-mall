# zeroai-simulator

多用户并发仿真压测端。独立 CLI 进程，**不进入任何业务服务的 classpath**。

设计说明与判定标准见 [`docs/SIMULATOR_P0_多用户并发仿真实施方案.md`](../docs/SIMULATOR_P0_多用户并发仿真实施方案.md)。

---

## 为什么是独立进程

不做成 Spring Boot Starter 塞进 `mobile-api`：那样压测流量会和被测服务抢同一份 CPU、
Tomcat 线程池和 GC，量出来的是「自己压自己」，而且一旦配置泄漏到生产就是一个后门。

## 三个维度互相独立

| 维度 | 配置 | 作用 |
|---|---|---|
| 并发用户数 | `sim.account.size` | 多少个不同身份在操作，决定数据分布与真实感；**同时是并行行为的上限** |
| 在途上限 | `sim.load.workers` | 同时在途请求数，决定能否把 λ 打满 |
| QPS 硬约束 | `sim.load.stages[].targetQps` | 开环令牌桶，与上面两者无关 |

「1000 个用户并发」= 1000 个账号轮转 + 200~400 个线程在途，不是开 1000 个线程。
调压力只动 `stages`，不要跟着改 `workers`。

> **账号数是硬上限**：同一账号同一时刻只允许一轮行为在跑（`AccountPool.lease()`）。
> 真实用户不会同时下两个单；不互斥会让「A 线程刚加购、B 线程下单把购物车消费掉」，
> 制造出系统里本来不存在的故障。所以想提高并发，先加账号，别只加线程。

## 快速开始

```bash
JAR=zeroai-simulator/target/zeroai-simulator.jar
CFG=zeroai-simulator/conf/sim-local.yml

# 0. 造数（MySQL 商品/SKU/秒杀活动 + Redis 秒杀库存预热 + ES 索引重建）
bash scripts/dev/seed-sim-dataset.sh

# 1. 构建（默认 mvn package 不含本模块）
JAVA_HOME=<jdk21> mvn -Psim -pl zeroai-simulator clean package -DskipTests

java -jar $JAR warmup   -c $CFG --size 500   # 预热账号（幂等）
java -jar $JAR smoke    -c $CFG              # 单用户验证链路，先跑这个
java -jar $JAR baseline -c $CFG              # 记录库存/订单基线
java -jar $JAR run      -c $CFG --yes        # 正式压测
sleep 90 && java -jar $JAR verify -c $CFG    # 等超时关单窗口过去再对账
```

产物：`sim-data/report-<时间戳>/{summary.html, metrics.csv}`

## 四份配置各自的用途

| 配置 | 场景 | 用途 |
|---|---|---|
| `conf/sim-local.yml` | `normal_purchase` | 常规压测（含 30% 放弃加购 / 15% 不支付的真实漏斗） |
| `conf/sim-smoke.yml` | `normal_purchase` | **链路验证专用**：分支概率置 0，保证每轮必然走完下单→支付→发货→收货 |
| `conf/sim-seckill.yml` | `seckill` | 秒杀齐射（`mode: burst`），人数建议 ≤200（服务端 SKU 令牌桶容量） |
| `conf/sim-search.yml` | `search_only` | 纯读链路摸吞吐上限，不写库 |

`shipAndConfirm: true` 需要 `adminToken`。后台登录有图形验证码，本地用脚本现取（30 分钟有效）：

```bash
TOKEN=$(bash scripts/dev/get-admin-token.sh)
# 然后填进 conf 的 sim.account.adminToken
```

## 六个必须知道的实现约定

1. **计时从拿到令牌之后开始**。允许排队的等待时间不计入 RT，否则「限流越狠、延迟越高」的假象会污染 P99。
2. **成功率按业务码判定**，不是 HTTP 2xx。下单还要额外轮询 `searchResult`，因为下单是异步的，接口返回 200 只代表消息入了队。
3. **轮询不单独记指标**。`order.searchResult` / `seckill.result` 只记一条「从提交到可见」的端到端耗时 + 最终结果；中间每次「还没建好」是预期状态，逐次记录会把等待显示成一片失败。
4. **链路成功率与接口成功率分开统计**。单接口 99% × 8 个串行步骤 ≈ 92%，这个差值才是真实用户流失。
5. **支付用 `payType=99`（TestPayStrategy）**，只把订单置为已支付 + 更新虚拟销量，**不写支付流水、不发回调后置消息**。直接后果：`OrderStockSupport#confirmFrozenStockByOrderId` 不会被触发，`shop_goods_product.locked_stock` / `shop_seckill_sku.locked_stock` 会一直停在冻结态、`sold_stock` 不增长。
   - 这不影响对账里的恒等式 `number + locked = 初始值`（库存只是搬了个位置，没丢）；
   - 但**不能把「下单数 = 已售数」当作校验**，那是真实支付才有的口径；
   - 支付步骤的耗时也不能作为生产支付容量结论。
6. **`goodsIds` 非空时落单目标固定为仿真商品**，搜索接口照常请求（QPS 口径不变），避免随机命中真实商品污染 `actual_sales` 与库存。

## 本地环境的两个硬瓶颈（压不出高 QPS 不是压测端的问题）

1. **订单消费端是单 listener**（`prefetch=1` + 手动 ack + 失败重试阻塞）。实测下单 QPS 超过 ~10 就会开始积压，`order_direct_queue` 的 `messages_ready` 会持续上涨。
   - 判断方法：`curl -s -u guest:guest http://127.0.0.1:15673/api/queues/%2F | jq '.[].messages'`
   - 积压时看到的是「下单接口 200 但订单迟迟不出现」，那是消费吞吐不足，不是下单接口坏了。
2. **毒消息会把整个队列卡死**。一条消费失败的消息会重试 3 次、每次约 5 秒，期间同一个 listener 什么都干不了。仿真数据被清过、或服务重启过，队列里就会残留这种消息。
   - 处理：`curl -s -u guest:guest -X DELETE http://127.0.0.1:15673/api/queues/%2F/order_direct_queue/contents`
   - 压测前清一次队列，是保证「失败原因可归因」的前置动作。

## 停机与保护

- 只允许打本机/内网地址（`localhost` / `127.*` / `192.168.*` / `10.*` / `172.1[6-8].*`），其他地址直接拒绝启动。
- `run` 必须显式加 `--yes`。
- 停机是优雅的：等在途请求跑完再退，避免把收尾请求记成失败导致成功率尾部假下跌。
