# SIMULATOR P0：多用户并发仿真实施方案

> 目标读者：实现者。本文按「读完即可动手」的粒度编写，所有结论都标注了代码锚点。
> 版本：P0（2026-10-26）。范围：**几百 ~ 上千虚拟用户并发执行真实业务链路，并控制整体 QPS**。

## 0. 当前进度（2026-10-26）

| 阶段 | 状态 | 说明 |
|---|---|---|
| S0 模块骨架 + ApiClient | ✅ 完成 | `mvn -Psim` 可构建出单 jar（10MB） |
| S1 账号池 + token 预热 | ✅ 完成 | JSONL 增量落盘，幂等补号；真实登录链路已跑通 |
| S2 普通购买全链路 | ✅ 完成 | 搜索→详情→加购→下单→支付→发货→收货，真机 100% |
| S3 开环阶梯 + 并发 | ✅ 完成 | 真机实测 5/15 目标 → 4.9/14.1，搜索链路 20/60 → 19.9/60.0 |
| S4 指标 + 报告 | ✅ 完成 | console + CSV + HTML；轮询聚合为一条端到端耗时 |
| S5 秒杀齐射 | ✅ 完成 | 40 人齐射 100% 成功，库存恒等式对平 |
| S6 对账 | ✅ 完成 | P0 门槛 C1 超卖 / C2 秒杀库存恒等式 / C3 订单幂等 / C4 商品库存恒等式，真机 6 项全绿；C5~C7 归 P1（见 §10） |
| 造数脚本 | ✅ 完成 | `scripts/dev/seed-sim-dataset.{sql,sh}` + `clean-sim-dataset.sql` |
| 真实后端联调 | ✅ 完成 | 三个服务（82/81/85）全链路联调通过，见 §18 |

> 报告说明：真机联调覆盖的是**业务系统 + 压测端**的完整闭环；
> 桩服务阶段只验证了压测端自身的到达率与口径精度。

---

## 1. 目标与非目标

### 1.1 目标

| 编号 | 目标 | 验收口径 |
|---|---|---|
| G1 | N 个虚拟用户（默认 500，上限按单机实测）并发执行业务链路 | N 个 sa-token 会话同时有效，无重登风暴 |
| G2 | 覆盖真实链路：搜索 → 商品详情 → 加购 → 下单 → 支付 → 发货 → 收货 | 单用户链路成功率 ≥ 99%（不含业务性拒绝） |
| G3 | 覆盖秒杀链路：活动列表 → 详情 → 秒杀 token → 提交 → 结果轮询 | 能压出卖光/限购/幂等拒绝的真实分布 |
| G4 | 全局 QPS 可控（开环固定到达率），支持阶梯 ramp | 实测到达率与配置值偏差 ≤ 5% |
| G5 | 压完能判定「系统是否出错」，不只是「跑完了」 | 对账 6 项全绿，见 §10 |

### 1.2 非目标（本期明确不做）

- **不做成 Spring Boot Starter 或 `@Scheduled` 任务嵌进 `mobile-api`**。压测流量与被测服务争 CPU / 线程池 / GC，数据不可信，且存在生产误开风险。
- 不测支付渠道的真实容量（`payType=99` 不走渠道，见 §2.8）。
- 不做分布式压测集群调度（P0 支持多进程手动分片，见 §7.4）。
- 不改动任何生产业务代码。**本方案需要零业务代码改动**。

---

## 2. 现状确定性结论（实现前必须知道）

以下每条都在当前代码里核实过，直接决定实现方式，不是推测。

### 2.1 登录不需要真发短信，但发送有冷却

`zeroai-mobile-api/.../member/LoginController.java`

```java
String code = "1234";
if (profileUtil.isProd()) { code = RandomUtil.randomNumbers(4); /* 真发短信 */ }
```

非 prod 环境验证码恒为 `1234`，只写入 Redis。但 `genMobileCode` 有 `MOBILE_CODE_SEND_CACHE` 冷却（`ttl > 0` 直接抛 `YZM_ENTER_BUSY_ERROR`）。

**推论**：账号池必须「预热一次 → 把 token 持久化到文件 → 运行时复用」。绝不能每个请求走登录。
**副作用**：每个新用户首次登录会创建 `shop_member` 记录并发放注册券（`LoginController` 中 `type=1` 的 `shop_coupon` 全部发一遍）。1000 个账号 = 1000 条会员 + N 张券，压测前要把这个基线记录下来，否则会误判为「优惠券超发」。

### 2.2 鉴权：裸 token 放 `Authorization` 头

`MobileAuthorizationHeaderFilter` 会把 `Bearer xxx` 裁成裸 `xxx`，两种写法都兼容。压测客户端统一用**裸 token**，少一层字符串处理。

### 2.3 下单是异步的，同步返回不代表成功

`OrderController.submit` → `iMobileOrderService.asyncSubmit(...)` 只返回 `orderSn`，真正建单在 `zeroai-message-consumer` 进程里。

**推论**：必须 `GET /order/searchResult/{orderSn}` 轮询判定成败。**只看 submit 的 HTTP 200 会得到虚假成功率，这是压测最常见的误判。**

轮询窗口按 `workload.orderPoll` 配置，默认 **30 × 500ms = 15 秒**。不能设成几百毫秒：本地 `local_message` 补偿表是定时扫描转发的，实测端到端建单常要 **3~4 秒**，窗口太短会把「系统慢」误判成「系统错」（这正是最初 `chain.order_not_persisted` 的根因）。
轮询过程**不能逐次记指标**，否则报表成功率会被前几次「还没落库」的失败拉到很低（实测曾只有 33%）；只记一条最终结果 + 一条端到端耗时。

### 2.4 秒杀是四段式，token 跳不过

`SeckillController` + `SeckillTokenSupport`：`token/{activitySkuId}` 签发 UUID 存 Redis，TTL **60 秒**，key 由 `activitySkuId + userId` 决定；`SeckillOrderSubmitSupport` 在 Redis Lua 里原子校验 token + 库存 + 限购。

**推论**：先拿 token 再提交。跳过 token 等于测一条生产上不存在的路径，秒杀防刷与 Lua 分支覆盖率会失真。
**注意**：token 60s 过期，齐射前 `T` 秒内批量签发，不要提前太久。

### 2.5 发货只在后台，收货要求已发货

- 发货：`POST /shop/order/ship`，body `{orderId, shipChannel, shipSn}`，走 `AdminOrderShipmentSupport`（带当前状态条件更新，防重复发货）。
- 收货：`POST /order/confirm/{orderId}`，要求订单处于 `STATUS_SHIP(301)`（`OrderStateTransitionSupport` 校验）。

**推论**：仿真链路跨 mobile + admin 两个鉴权域，账号池需要**额外持有一个后台管理员 token**。不需要为此新增接口。

### 2.6 订单状态机

`OrderStatusEnum`：`101 未付款 → 201 已付款 → 301 已发货 → 401 用户收货`；`102/103` 为取消；`402` 系统超时收货。

### 2.7 未支付订单会被延迟消息自动取消（本地实测 60 秒）

存在 `UnpaidOrderController` + `local_message` + RabbitMQ 延迟消息。本地已验证的超时关单窗口是 **60 秒**（下单 → Redis 预扣 → MySQL 冻结 → 本地消息 → MQ → 消费者落库/超时关单回补库存）。

**推论**：压测产生的大量未支付单会在压测结束后 60 秒内陆续被取消并回补库存。
- 对账必须等 **压测结束 + 90 秒** 后再执行，否则会读到「库存还在变」而误判。
- 反过来这也是个免费的正确性检查点：未支付单的库存回补如果没发生，C2 恒等式就会挂。

### 2.8 模拟支付按钮 = `payType=99`，它跳过的是整个回调链路

`zeroai-payment-channel/.../pay/TestPayStrategy.java`，`PayTypeEnum.TEST(99, "测试支付")`：

```java
order.setPayId(NanoId.randomNanoId());
order.setPayTime(LocalDateTime.now());
order.setOrderStatus(OrderStatusEnum.STATUS_PAY.getStatus());
orderService.updateById(order);
updateVirtualSales(order.getId());   // 只更新虚拟销量
```

对比真实回调 `PaymentCallbackSupport.epayPayNotify`：验签 → 金额校验 → `OrderUtil.hasPayed` 幂等判断 → 状态机 `canTransition` 校验 → 事务内写 `shop_payment_flow` + `shop_order_status_log` + 本地消息 → MQ 后置动作。

**必须写进报告的结论**：

> `payType=99` 只做「订单置 201 + 虚拟销量更新」，**不写支付流水、不写状态日志、不发本地消息**。
> 因此支付这一步的耗时与 QPS **不能作为生产支付容量依据**。它的定位是「把状态机推进到已支付」，好让链路继续走到发货与收货。
> 压测报告中「支付环节 RT」只用于判断数据库写压力，不用于容量结论。

---

## 3. 总体架构

独立 CLI 进程，与业务服务物理隔离。

```
┌─────────────────────────┐
│  zeroai-simulator (CLI) │   ← 单机默认 1 进程，可水平起 N 个进程
│  ├ 账号池 (token 文件)   │
│  ├ 开环令牌桶 (λ)        │
│  ├ 虚拟线程 × VU         │
│  └ 指标 + 对账           │
└───────┬─────────┬───────┘
        │ HTTP    │ HTTP
        ▼         ▼
  mobile-api(82)  admin-api    ← 已有
        │
        ▼
  message-consumer            ← 必须同时启动，否则下单只进队列不落库
        │
        ▼
  MySQL / Redis / RabbitMQ / ES
```

**关键点**：
1. 必须有 `message-consumer` 同时在跑，否则 `searchResult` 永远轮询不到成功 —— 这不是压测失败，是环境没起全。
2. 建议用 `docker-compose.local.yml` 起依赖，`jars/` 下已打包好的 jar 直接跑，流程参考 `docs/P0_SMOKE_CHECKLIST.md`。

---

## 4. 模块结构

在根 `pom.xml` 以 **profile 方式**挂载，保证默认 `mvn clean package` 不构建它、不进 CI 主链路、不进任何 api 模块的 classpath：

```xml
<profile>
    <id>sim</id>
    <modules>
        <module>zeroai-simulator</module>
    </modules>
</profile>
```

构建：`mvn -Psim -pl zeroai-simulator -am clean package`，产物 `zeroai-simulator/target/zeroai-simulator.jar`。

```
zeroai-simulator/
├── pom.xml                                 依赖：okhttp + fastjson2 + snakeyaml + mysql-connector-j(runtime)
├── conf/sim-local.yml                      本地配置
└── src/main/java/com/zeroai/simulator/
    ├── SimLauncher.java                    main：warmup / smoke / baseline / run / verify
    ├── config/SimConfig.java               配置读取（snakeyaml + 点路径取值），含 Stage 记录
    ├── auth/
    │   ├── AccountPool.java                账号池（JSONL 增量落盘、轮转取号）
    │   ├── TokenWarmup.java                并发预热 token + 默认地址
    │   └── VirtualUser.java                身份三元组 mobile / token / addressId
    ├── client/
    │   ├── ApiClient.java                  OkHttp 同步调用 + 令牌桶 + 业务码解包 + 指标埋点
    │   ├── MobileApi.java                  移动端接口映射（含 payType=99 模拟支付）
    │   ├── AdminApi.java                   管理端只有 ship + token 校验
    │   └── ApiResponse.java                业务码 / HTTP / 原始报文
    ├── engine/Engine.java                  开环阶梯 + 齐射两种模式，工作线程池与优雅停机
    ├── scenario/
    │   ├── Scenario.java                   场景契约（run / prepare）
    │   ├── AbstractScenario.java           轮询判定、支付校验、中断原因埋点
    │   ├── SearchOnlyScenario.java         纯搜索（无写入，最高 QPS）
    │   ├── PurchaseScenario.java           搜索→详情→加购→下单→支付→（发货→收货）
    │   └── SeckillScenario.java            token→提交→轮询→支付，支持齐射预签
    ├── metric/
    │   ├── Metrics.java                    接口级 / 链路级 / 逐秒三层口径
    │   ├── StepStat.java / LatencyRecorder.java
    │   └── ReportWriter.java               console + CSV + HTML
    ├── util/
    │   ├── RateLimiter.java                开环令牌桶（有界队列，阻塞取许可）
    │   └── ThinkTime.java                  对数正态思考时间
    └── verify/Reconcile.java               baseline / verify 对账（JDBC 只读）
```

**依赖边界**：只引入 okhttp / fastjson2 / snakeyaml / mysql-connector-j，**不依赖任何 `zeroai-*` 模块**。

> 这里与初版方案有一处偏离：原计划复用 `zeroai-domain-api` 的 Request/Response 模型，
> 实测后发现它会把 Spring、MyBatis-Plus、Lombok 一并拖进 CLI，启动变慢且容易与业务依赖冲突。
> 改为「路径 + 字段名」直接映射，接口契约变化由 `smoke` 子命令第一时间暴露，
> 代价可接受，收益是压测端可以独立打包、独立运行。

---

## 5. 账号池（1000 用户的正确预热方式）

### 5.1 号码规划

专门号段，便于清理与识别：`199` + 8 位序列，例如 `19900000001` ~ `19900001000`。

> 与真实用户号段隔离，是后续能按 `mobile LIKE '1990000%'` 精确清理的前提。

### 5.2 预热流程（一次性，约 1~2 分钟跑完 1000 账号）

```
for each mobile in pool (并发度 16，避免打爆 DB):
    1. POST /genMobileCode  {mobile}            // 非 prod 不发短信，写 Redis
    2. POST /login          {mobile, yzm:1234}  → token
    3. GET  /address/list                       // 若为空：
       POST /address         → 默认地址          // 注意：不是 /address/add，会 404
       GET  /address/list   → addressId
    4. 落盘 {mobile, userId, token, addressId}
```

落盘：`sim-data/accounts-<env>.jsonl`，一行一个用户。

### 5.3 运行时约束

- **token 复用，不重登**。sa-token 会话在 Redis，1000 个 token 常驻没有压力。
- **长稳测试（> 2h）要续期**：每 30 分钟抽 5% 账号重登一次，刷新 TTL。
- **登录并发度压到 16**：`genMobileCode` 有冷却、登录会写 `shop_member` + 发券，并发太高会把预热阶段的 DB 打满，污染压测基线。
- **admin token 单独预热一个**：后台 `sys_user` 账号登录，仅用于发货，不参与 QPS 统计。
- **同一账号同一时刻只能有一轮行为在跑**（`AccountPool.lease()/release()` 租约）。这是正确性要求而非性能优化：多线程共用一个账号会出现「A 线程加购、B 线程下单把购物车消费掉」的自造故障，表现为 `cart.delete` 大面积 `BIZ_500`，会被误读成系统 bug（实测失败率 72%，加租约后 0%）。

---

## 6. 行为编排

### 6.1 两条主链路（严格按真实顺序）

**A. 普通购买（`NormalPurchaseScenario`）**

```
GET  /search/result?keyword={随机词}&pageNum=1&pageSize=10     ← 每次 1 个随机词
GET  /goods/detail/{goodsId}                                    ← 从搜索结果里随机取
POST /cart            {goodsId, productId, number:1}            ← 约 30% 用户在此放弃（不加购）
GET  /cart/getCheckedGoods                                      ← 确认金额与 couponList
POST /order/submit    {cartIdArr, addressId, payType:99}
     ↓ 异步
轮询 GET /order/searchResult/{orderSn}                           ← 最多 10 次 × 200ms
POST /pay/prepay      {orderSn, payType:99}                      ← 模拟支付，跳到 201
GET  /order/detail/{orderSn}                                     ← 取 orderId 与 actualPrice
[后台] POST /shop/order/ship {orderId, shipChannel, shipSn}      ← 每 5 单合并一次批量发货
POST /order/confirm/{orderId}                                    ← 收货，101→201→301→401 闭环
```

**B. 秒杀（`SeckillScenario`）**

```
GET  /seckill/list                                    ← 取已发布活动
GET  /seckill/detail/{activityId}                     ← 取 activitySkuId
GET  /seckill/token/{activitySkuId}                   ← 60s TTL，齐射前签发
POST /seckill/submit  {activitySkuId, number:1, addressId, seckillToken}
轮询 GET /seckill/result/{orderSn}                     ← 最多 10 次 × 200ms
（成功则继续走 支付 → 发货 → 收货，同链路 A）
```

### 6.2 真实感参数（不要用固定值）

| 参数 | 建议分布 | 说明 |
|---|---|---|
| think time | 对数正态 μ=ln(800ms), σ=0.6，截断 [200ms, 5s] | 固定 sleep 会打出「脉冲」流量，形态不像人 |
| 搜索词 | 从 `shop_keyword` 表取真实热词 + 10% 随机串 | 覆盖命中与未命中两条分支 |
| 商品 | 从预置 sim 商品池随机 | 见 §9.1 |
| 加购放弃率 | 30% | 只浏览不下单，制造真实漏斗 |
| 下单不支付率 | 15% | 制造未支付单 → 触发超时取消链路，这是真实场景 |
| 分页/排序 | 随机 `isPrice/isSales/orderBy/filterNew` | 覆盖 ES 查询分支 |
| UA / 设备 | 3~5 个固定 UA 轮转 | 避免网关侧 UA 黑名单差异 |

### 6.3 线程数与 QPS 的关系（容易搞混）

`workers` 数量 ≠ QPS。开环模式下两者独立：λ 决定 QPS，workers 只是「同时在途的请求上限」。

- 压 500 QPS、链路平均 400ms、每用户每轮 8 个串行请求 → 在途需求 ≈ 500 × 0.4 = 200，workers 给 400 有富余。
- **workers 太小会变成假瓶颈**（线程不够，实测 QPS 上不去但系统没满），workers 太大只是增加上下文切换，QPS 不变。
- 经验配比：`workers ≈ 期望QPS × 平均RT秒数 × 1.5`，下限 64。
- 判据：报告里「实测 QPS < 目标 QPS」且「平均调度等待 ≈ 0」→ 线程不够；「平均调度等待很大」→ 确实是 λ 喂不进去，属正常节流。

---

## 7. 并发与 QPS 控制

### 7.1 线程模型：三个维度解耦

| 维度 | 配置项 | 作用 | 典型值 |
|---|---|---|---|
| 并发用户数 | `account.size` | 有多少个不同身份在操作，决定真实感与数据分布 | 500 ~ 1000 |
| 在途上限 | `load.workers` | 同时在途的请求数，决定能不能把 λ 打满 | 200 ~ 400 |
| 到达率 λ | `load.stages[].targetQps` | QPS 硬约束，与上面两者无关 | 20 ~ 800 |

**为什么不用虚拟线程**：OkHttp 的连接获取路径含 `synchronized` 块，在虚拟线程下会 pin 住载体线程；
1000 VU 时反而比平台线程更差。改为**有界平台线程池 + 令牌桶**：QPS 由 λ 保证，
线程只负责「跟上 λ」，账号池轮转保证所有身份都被用到。
因此「1000 用户并发」= 1000 个账号轮转 + 200~400 个线程在途，不是开 1000 个线程。

### 7.2 开环令牌桶（全局到达率）

```java
// ArrivalScheduler 核心：每 tickMs 发放 λ*tick 个许可
// 用有界队列 + 满载丢弃，模拟「系统过载后客户端请求被直接拒绝」
scheduler.scheduleAtFixedRate(() -> {
    int permits = (int) Math.floor(lambda * tickMs / 1000.0 + carry);  // carry 为小数累积
    for (int i = 0; i < permits; i++) {
        if (!permitQueue.offer(TOKEN)) dropped.increment();   // 丢弃计入 backpressure
    }
}, 0, tickMs, MILLISECONDS);
```

- `tickMs = 10`，λ 精度足够。
- **有界队列是关键**：无界队列会让「系统变慢」表现为内存增长而不是丢弃，测不出过载行为。
- 被丢弃的许可单独计数 `backpressure_dropped`，报告中单列，**不计入失败率**。

### 7.3 阶梯 ramp

```yaml
stages:
  - { duration: 60s, targetQps: 50 }     # 预热
  - { duration: 120s, targetQps: 100 }
  - { duration: 120s, targetQps: 200 }
  - { duration: 120s, targetQps: 400 }
  - { duration: 120s, targetQps: 800 }   # 找拐点
  - { duration: 60s,  targetQps: 800 }   # 稳态观察
```

每档结束打一行快照：`qps / 成功率 / P50 / P95 / P99 / 错误码分布 / backpressure_dropped`。**拐点判定：P99 超过上一档 1.5 倍，或错误率 > 1%，或 backpressure_dropped 突增。**

### 7.4 秒杀齐射（burst）

秒杀不是均匀到达，是**同一秒内爆发**。实现：独立的 `burstAt` 配置，在指定时间戳把所有已签发 token 的用户同时唤醒。

```
T-30s   批量签发 token（60s TTL 内）
T-0s    同时释放 N 个 VU（N = 配置的 burstUsers，如 1000）
T+0~10s 轮询结果
```

齐射与开环令牌桶互斥：秒杀用 burst 模式，普通链路用 token 桶模式。

### 7.5 多进程分摊（超过单机上限时）

`λ_per_process = totalλ / processCount`，进程间无需额外协调。前提：**每进程必须显式配置自己的 `processIndex`**，否则 N 个进程各打满 λ，实际 QPS 是 N 倍——这是最常见的翻车点。

### 7.6 真正的瓶颈通常不在压测端，而在这三处

| 位置 | 默认值 | 1000 VU 下必然发生什么 | 处理 |
|---|---|---|---|
| Tomcat `max-threads` | 200 | 请求排队，RT 里混入排队时间，测的不是业务耗时 | 显式配置并记录到报告头（如 500），否则容量结论无效 |
| HikariCP `maximumPoolSize` | 10 | 连接等待成为主导延迟 | 按 `必要连接数 ≈ QPS × 平均DB耗时(ms) / 1000` 估算；500QPS×20ms≈10 连接刚好，压到 800QPS 必须调大 |
| 客户端连接池 | HC5 默认 | 连接复用不足，出现 `ConnectionRequestTimeout` | `maxTotal = maxPerRoute = VU × 2`，`validateAfterInactivity=2000` |

**报告必须写明本次压测的服务端线程池与连接池配置**，否则数字不可复现、不可比。

---

## 8. 秒杀与库存的关键时序

```
Redis Lua 原子操作（luaAcquireSeckillStock）
  入参：stockKey / userKey / tokenKey / resultKey
  校验：token 匹配 → 库存充足 → 限购未超
  动作：available_stock-1, locked_stock+1
        ↓
  创建订单（101 未付款）
        ↓
  支付成功 → locked_stock-1, sold_stock+1
  或超时取消 → locked_stock-1, available_stock+1（回补）
```

**恒等式**（对账核心，见 §10）：`available_stock + locked_stock + sold_stock = 初始总库存`。

---

## 9. 压测数据准备

### 9.1 专用 sim 数据集（必须隔离）

| 对象 | 要求 | P0 实际落地 | 扩容建议 |
|---|---|---|---|
| 手机号 | `1990000xxxx` 段 | `1990000%`，由 warmup 真实登录创建 | ≥ VU 数 × 1.2 |
| 商品 | `goods_name` 前缀 `SIM-`，独立分类 | 3 个（9900001~9900003） | 50 个 |
| SKU | 每商品 1~3 规格，库存 ≥ 10000 | 3 个，`number=100000` | 120 个 |
| 秒杀活动 | 标题前缀 `SIM-`，独立活动，`status=1` | 1 个（990001），时间窗覆盖当前 | 3 个，每 SKU 库存 500 |
| 秒杀 SKU | 与活动绑定 | 1 个（9900001），`available_stock=1000`、`limit_count=1` | 按活动数递增 |
| 地址 | 预热时自动创建，收件人 `SIM-<mobile后4位>` | `POST /address` 每用户 1 条 | 同左 |

**ID 规划**：所有 SIM 对象用高位固定 ID（商品 9900001~9900099、活动 990001），这样清理可以按 ID 精确命中，**不依赖时间范围**——时间范围清理会误删同窗口的真实数据。

**为什么必须隔离**：`shop_search_history`、`shop_keyword`、ES 索引与热词榜都会被仿真数据写入，进而污染搜索特征与推荐侧数据。带 `SIM-` 前缀是唯一能让它们「可识别、可清理」的手段。

### 9.2 造数脚本

三条命令缺一不可——只灌 MySQL 会漏掉 Redis 库存与 ES，压测直接表现为「库存不足」或「搜不到商品」：

| 脚本 | 作用 |
|---|---|
| `scripts/dev/seed-sim-dataset.sql` | 灌 SIM 商品 / SKU / 秒杀活动（先 DELETE 再 INSERT，可重复执行） |
| `scripts/dev/seed-es-goods.py` | 重建 ES 商品索引（实测 233 条文档） |
| `scripts/dev/seed-sim-dataset.sh` | 上面两步 + **Redis 秒杀库存预热**，一条命令跑完 |
| `scripts/dev/clean-sim-dataset.sql` | 按 ID 集合与号段精确清理，先子表后主表 |

配套脚本：
- `scripts/dev/set-login-code.py` —— 跳过短信验证码（warmup 也可走真实 `genMobileCode`，本地两条路都通）
- `scripts/dev/init-db-local.sh` —— 初始化表结构
- `scripts/dev/get-admin-token.sh` —— 后台 token（读 Redis 绕过图形验证码）

> Redis 预热的关键细节：`zeroai:seckill:stock:{skuId}` 必须是**裸整数**，且值取自 DB 的 `available_stock`。写 JSON 或带引号会让 Lua `tonumber` 得 nil、接口返回 -2，现象酷似「卖光」。

---

## 10. 判定标准：对账（本方案最有价值的部分）

压测的产出不是 QPS 数字，是**「系统在高并发下有没有算错」**。以下各项在**跑前记录基线、跑后比对**，任一不通过即整体判定失败。

| # | 检查项 | 判定 | 实现状态 |
|---|---|---|---|
| C1 | **超卖** | 任何 SKU `sold_stock > 初始库存` → 失败 | ✅ `Reconcile` 已实现 |
| C2 | **秒杀库存恒等式** | `available + locked + sold = 初始`，逐 SKU 校验 | ✅ 已实现（基线取自 `baseline`） |
| C3 | **订单幂等** | 同 `member_id` + 同 `goods/product` + 2s 内重复 `order_sn` = 0 | ✅ 已实现 |
| C4 | **商品库存恒等式** | `shop_sku.number + locked_stock` 压测前后必须相等 | ✅ 已实现（普通链路唯一的库存正确性断言，同时覆盖冻结与超时回补） |
| C5 | **库存流水可追溯** | `shop_inventory_flow` 中 `flow_key` 无重复，且流水条数与状态跃迁匹配 | ⏳ P1：`payType=99` 不写支付流水，需先做渠道豁免 |
| C6 | **状态机合法** | `shop_order_status_log` 中不存在非法跃迁（如 101→301 直跳） | ⏳ P1 |
| C7 | **秒杀限购** | 任一 `(member_id, seckill_sku)` 订单数 ≤ `limit_count` | ⏳ P1：burst 场景必做，是最容易超发的地方 |

> C1~C4 是 P0 的准入门槛，已在真机联调中全部 PASS（见 §18.3）。C5~C7 记在 P1：它们查的是「记录是否完整可信」，不影响「库存有没有算错」这个核心结论。

### 10.1 对账 SQL（以实际表结构微调）

```sql
-- C2 库存恒等式（shop_seckill_sku）
SELECT id, available_stock, locked_stock, sold_stock,
       (available_stock + locked_stock + sold_stock) AS total,
       (available_stock + locked_stock + sold_stock) <> /* 初始总库存 */ 0 AS broken
FROM shop_seckill_sku WHERE activity_id IN (/* sim 活动 ID */);

-- C3 疑似重复单（同用户同商品 2s 内多单）
SELECT member_id, goods_id, COUNT(*) c, MIN(create_time), MAX(create_time)
FROM shop_order o JOIN shop_order_goods g ON g.order_id = o.id
WHERE o.create_time >= /* 压测开始 */ 
GROUP BY member_id, goods_id, UNIX_TIMESTAMP(o.create_time) DIV 2
HAVING c > 1;

-- C7 秒杀限购
SELECT r.member_id, r.activity_sku_id, COUNT(*) c
FROM shop_order_activity_relation r
WHERE r.create_time >= /* 压测开始 */
GROUP BY r.member_id, r.activity_sku_id
HAVING c > (SELECT limit_count FROM shop_seckill_sku WHERE id = r.activity_sku_id);

-- 支付流水（注意：payType=99 不写流水，此项对本方案需按渠道豁免）
SELECT COUNT(*) FROM shop_payment_flow WHERE create_time >= /* 压测开始 */;
```

> ⚠️ 上面列名如与当前表结构不一致，以 `db-init/*.sql` 为准；`shop_order` 与 `shop_order_activity_relation` 的关联列名请在实现时用 `SHOW COLUMNS` 确认，不要照抄。

### 10.2 报告头必须包含（否则数字不可比）

```
服务端配置：tomcat.max-threads / hikari.maximumPoolSize / JVM -Xmx / CPU 核数
压测端配置：VU / λ / 进程数 / 客户端连接池
数据基线：初始库存、sim 商品数、账号数
窗口：压测开始/结束时间 + 是否覆盖未支付超时取消窗口（见 §2.7）
```

---

## 11. 指标定义

| 指标 | 定义 | 注意 |
|---|---|---|
| `qps_actual` | 实际发出请求数 / 秒 | 与 `targetQps` 对比，偏差 > 5% 说明调度有问题 |
| `success_rate` | 按业务判定，不按 HTTP 2xx | 下单成功 = `searchResult` 返回 success，不是 submit 200 |
| `error_dist` | 按 `R.code` 分桶，不按异常类型 | 业务拒绝（如「已售罄」）**不算失败**，单列 `biz_reject` |
| `latency` | 每接口 P50/P95/P99，HDR Histogram | 轮询类接口单独统计，不要混入主链路均值 |
| `backpressure_dropped` | 令牌桶满载丢弃数 | 过载信号，单列 |
| `mq_lag` | RabbitMQ 队列深度（管理端 API 拉取） | **下单成功率 100% 但 MQ 积压 = 实际过载** |

**链路级成功率**：以「用户完整走完一轮」为单位统计，比单接口成功率高价值 —— 单接口 99% × 8 个串行步骤 ≈ 92%，这个差值是真实用户流失率。

---

## 12. 配置 schema

`zeroai-simulator/conf/sim-local.yml`（与仓库内文件保持一致）：

```yaml
sim:
  baseUrl: "http://127.0.0.1:82"
  adminBaseUrl: "http://127.0.0.1:81"
  processIndex: 0
  processCount: 1                             # 多机压测时 λ 自动按此分摊

  account:
    poolFile: "sim-data/accounts-local.jsonl"
    size: 500                                 # 目标账号数（并行上限，不是线程数）
    mobilePrefix: "1990000"
    mobileStart: 1
    adminToken: ""                            # 仅 shipAndConfirm=true 时必填
                                              # 用 scripts/dev/get-admin-token.sh 现取

  workload:
    scenario: normal_purchase                 # search_only | normal_purchase | seckill
    keywords: ["SIM-仿真", "仿真测试", "SIM"]
    goodsIds: [9900001, 9900002]              # 非空则落单固定到这些仿真商品
    shipAndConfirm: true                      # 是否继续走 后台发货 + 确认收货
    abandonAfterCartRate: 0.30                # 加购后放弃（验证链路时置 0）
    leaveUnpaidRate: 0.15                     # 下单不支付（覆盖超时关单 + 库存回补）
    thinkTime: { minMs: 200, maxMs: 5000, medianMs: 800 }
    orderPoll:                                # 异步下单轮询窗口 30×500ms = 15s
      maxAttempts: 30
      intervalMs: 500
    seckill:
      activityId: 990001                      # 0 = 从详情接口自动解析
      activitySkuId: 9900001
      burstUsers: 100                         # 服务端 SKU 令牌桶 200/s，超过必被限流

  load:
    mode: open_loop                           # open_loop | burst
    tickMs: 10
    workers: 200                              # 在途上限 ≈ 目标QPS × 链路RT × 1.5
    stages:
      - { duration: 15, targetQps: 5 }
      - { duration: 30, targetQps: 15 }
    burstAtEpochMs: 0                         # 齐射时刻，mode=burst 时使用

  http:
    connectTimeoutMs: 1000
    readTimeoutMs: 5000
    maxIdleConnections: 400                   # 建议 ≥ workers

  report:
    intervalSec: 5
    outDir: "sim-data"

  verify:
    enabled: true
    jdbcUrl: "jdbc:mysql://127.0.0.1:3307/zeroai_mall?useSSL=false&serverTimezone=Asia/Shanghai"
    jdbcUser: "root"
    jdbcPassword: ""
    baselineFile: "sim-data/baseline.json"
```

三个「看起来该一起改、其实不该」的参数：
- **`account.size`（账号数）** —— 只影响用户分布的真实感与并行上限，不影响 QPS。
- **`load.workers`（线程数）** —— 只影响能否把目标 QPS 打满，不定义 QPS。
- **`load.stages[].targetQps`（λ）** —— 唯一的 QPS 硬约束。调压力只改这里。

> 本地端口约定见 §16：admin 81 / mobile 82 / consumer 85 / MySQL **3307** / Redis **6380** / RabbitMQ **5673** / ES **9200**。代码里不要写死 3306。

---

## 13. 分阶段实施（每阶段可独立验收）

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| **S0** | 模块骨架 + `ApiClient` + 单用户手工跑 `search` | 命令行能打印一次搜索的 RT 与结果数 |
| **S1** | `AccountPool` + `TokenWarmup`（1000 账号落盘） | 1000 token 全部可用，预热耗时 < 3min，无冷却报错 |
| **S2** | `NormalPurchaseScenario` 全链路单用户跑通 | 一个用户 101→201→301→401 走完，日志可追 |
| **S3** | `ArrivalScheduler` + 虚拟线程并发 | 配置 200 QPS，实测到达率偏差 ≤5%，500 VU 稳定 5min 无异常 |
| **S4** | `MetricsCollector` + `ReportWriter` | 输出 console + CSV + HTML，含 §10.2 报告头 |
| **S5** | `SeckillScenario` + burst 齐射 | 40 人以上齐射，能观察到「售罄」「限购」真实分布，C1/C2 全绿 |
| **S6** | `ReconcileChecker` + `Baseline` | 6 项对账全部自动执行并输出结论 |

**建议交付顺序**：S0 → S1 → S2 → S6（先把对账做出来，再往上加量）→ S3 → S4 → S5。理由：对账是判定标准，先有标尺再加负载，否则压出问题也说不清是谁的问题。

---

## 14. 风险与边界

| 风险 | 影响 | 对策 |
|---|---|---|
| 压测端成为瓶颈 | 测出的是客户端排队 | S3 验收时先空跑一次「压测端自测」（打一个静态接口），确认客户端能打出目标 QPS |
| Tomcat/Hikari 默认值 | 容量结论完全错误 | §7.6，必须显式配置并写进报告头 |
| 未支付单超时取消 | 对账时库存仍在变 | 观察窗口覆盖超时窗口，或压测时关闭自动取消（需评估） |
| 仿真数据污染 ES/推荐 | 搜索热词、榜单失真 | §9.1 `SIM-` 前缀 + 专用号段，压后执行清理脚本 |
| `payType=99` 绕过回调 | 支付容量结论无效 | §2.8，报告显式声明局限 |
| 生产误跑 | 生产垃圾数据 | `sim.enabled` 只读本地 yml + baseUrl 校验（host 含 `localhost`/内网才允许启动）+ 启动二次确认 |

**额外的启动保护（建议）**：`SimLauncher` 启动时校验 `baseUrl` 主机名，若不在白名单（`localhost`、`127.0.0.1`、`*.local`、内网段）直接退出，并要求 `--yes` 参数才真正开始压测。

---

## 15. 附录 A：接口清单（已核实）

| 链路 | 方法 | 路径 | 鉴权 | 关键入参 |
|---|---|---|---|---|
| 登录 | POST | `/genMobileCode` | 否 | `{mobile}` |
| 登录 | POST | `/login` | 否 | `{mobile, yzm}` → `token` |
| 地址 | GET | `/address/list` | mobile | — |
| 地址 | POST | `/address`（**不是** `/address/add`） | mobile | `AddressSaveReqVO` |
| 搜索 | GET | `/search/result` | mobile | `keyword,pageNum,pageSize,isPrice,isSales,orderBy` |
| 搜索 | GET | `/search/hotKeywords` | 否 | — |
| 商品 | GET | `/goods/detail/{goodsId}` | 否 | — |
| 购物车 | POST | `/cart` | mobile | `CartAddReqVO{goodsId,productId,number}` |
| 购物车 | POST | `/cart/getCheckedGoods` | mobile | — |
| 下单 | POST | `/order/submit` | mobile | `OrderCommitReqVO{cartIdArr,addressId,payType:99}` |
| 下单 | GET | `/order/searchResult/{orderSn}` | mobile | — |
| 订单 | GET | `/order/detail/{orderSn}` | mobile | — |
| 支付 | POST | `/pay/prepay` | mobile | `{orderSn, payType:99}` |
| 后台发货 | POST | `/shop/order/ship` | admin | `{orderId, shipChannel, shipSn}` |
| 收货 | POST | `/order/confirm/{orderId}` | mobile | — |
| 秒杀 | GET | `/seckill/list` | 否 | — |
| 秒杀 | GET | `/seckill/detail/{activityId}` | 否 | — |
| 秒杀 | GET | `/seckill/token/{activitySkuId}` | mobile | — → 60s TTL |
| 秒杀 | POST | `/seckill/submit` | mobile | `{activitySkuId,number,addressId,seckillToken}` |
| 秒杀 | GET | `/seckill/result/{orderSn}` | mobile | — |

## 16. 附录 B：运行手册

### 16.1 本地端口（已实测，勿改）

| 服务 | 端口 | 备注 |
|---|---|---|
| admin-api | 81 | 后台，发货走这里 |
| mobile-api | 82 | 主压测目标 |
| message-consumer | 85 | **必须启动**，否则下单不落库 |
| MySQL | 3307 | 宿主 3306 已被占用，Colima 映射到 3307 |
| Redis | 6380 | 带密码 |
| RabbitMQ | 5673 / 15673 | 管理端用于取 MQ 积压 |
| ES | 9200 | elastic/123456 |

### 16.2 步骤

```bash
JAR=zeroai-simulator/target/zeroai-simulator.jar
CFG=zeroai-simulator/conf/sim-local.yml

# 0. 构建压测端（默认构建不含它，必须带 -Psim）
JAVA_HOME=<jdk21> mvn -Psim -pl zeroai-simulator clean package -DskipTests

# 1. 起依赖 + 三个应用（缺 message-consumer 则下单永远不落库）
docker compose -f docker-compose-es7-rabbitmq-redis-mysql.yml \
               -f docker-compose.local.yml up -d

LOG_PATH_PREFIX=./logs UPLOAD_DIR=./upload java -jar jars/zeroai-mobile-api.jar    &
LOG_PATH_PREFIX=./logs UPLOAD_DIR=./upload java -jar jars/zeroai-message-consumer.jar &
LOG_PATH_PREFIX=./logs UPLOAD_DIR=./upload java -jar jars/zeroai-admin-api.jar     &
# admin-api 只在压「发货 → 收货」链路时必须（否则订单永久停在 201）

# 2. 准备 sim 数据集：MySQL 造数 → Redis 秒杀库存预热 → ES 索引重建，一条脚本搞定
scripts/dev/seed-sim-dataset.sh
# 单步执行等价于：
#   mysql -h127.0.0.1 -P3307 -uroot -p < scripts/dev/seed-sim-dataset.sql
#   redis-cli -p 6380 SET zeroai:seckill:stock:9900001 <available_stock>   # 必须是裸整数
#   python3 scripts/dev/seed-es-goods.py

# 3. 预热账号（幂等，可重复执行补号）
java -jar $JAR warmup -c $CFG --size 500

# 4. 单用户验证链路（先跑这个，再压量）
java -jar $JAR smoke -c $CFG

# 5. 记录基线
java -jar $JAR baseline -c $CFG

# 6. 正式压测（必须 --yes）
java -jar $JAR run -c $CFG --yes

# 7. 等 90 秒（覆盖 60 秒未支付超时关单），再对账
sleep 90
java -jar $JAR verify -c $CFG
# → sim-data/report-<ts>/{summary.html, metrics.csv}

# 8. 清理
mysql -h127.0.0.1 -P3307 -uroot -p < scripts/dev/clean-sim-dataset.sql
```

> `run` 自带目标环境保护：host 不在 `localhost / 127.* / 192.168.* / 10.* / 172.1[6-8].*` 内会直接拒绝启动。

### 16.3 四份配置的分工

| 配置 | 用途 | 关键差异 |
|---|---|---|
| `sim-local.yml` | 常规压测 | `abandonAfterCartRate 0.30` / `leaveUnpaidRate 0.15`，阶梯 `15s@5 → 30s@15` |
| `sim-smoke.yml` | 链路走通验证 | 两个概率置 0，`shipAndConfirm: true`，保证每轮必走完下单→支付→发货→收货 |
| `sim-seckill.yml` | 秒杀齐射 | `mode: burst`、`burstUsers: 40`、`activitySkuId: 9900001` |
| `sim-search.yml` | 纯搜索压 QPS | `scenario: search_only`，阶梯 `15s@20 → 15s@60` |

### 16.4 后台 token 怎么拿（跳过图形验证码）

后台登录有验证码，但码明文存在 Redis，可以读出来回填，走**真实登录接口**拿 token：

```bash
scripts/dev/get-admin-token.sh          # 内部：GET /captcha → 读 Redis → POST /login
```

> 后台账号实测密码为 `123456`。这是本地联调便利脚本，不要带进任何共享环境。

---

## 17. 已确认的边界（实测结论，替代原「待确认项」）

原文档列的三条待确认项，都已由真机联调给出答案；另外补了三条实现期才发现的事实。

1. **单机上限：瓶颈不在数据库，在消费端单 listener。**
   实测 15 QPS（其中下单约 4.5 QPS）稳定无积压；一旦下单速率逼近 ~10 QPS，`order_direct_queue` 开始单调积压、本地消息表 `status=1` 同步堆积，随后建单端到端耗时突破 15 秒轮询窗口，表现为 `chain.order_not_persisted`。
   根因是消费端 `prefetch=1` + 单 listener，一条毒消息重试 3×5 秒就能把整个队列堵死。
   → 本地阶梯因此压到 `15s@5 → 30s@15`。要跑几百上千 VU，必须扩 `message-consumer` 并发（多 listener / 提高 prefetch / 毒消息快速转 DLQ），**调压测端的口径没有意义**。

2. **对账 SQL 的关联列：已按实际表结构落成代码，不需要再手改。**
   `Reconcile` 直接读 `shop_sku` / `shop_goods` / `shop_seckill_activity_sku` / `shop_order` / `shop_order_goods` 的真实列，C1~C4 在真机上全部 PASS（见 §18.3）。

3. **admin-api 必须常驻**（限「发货 → 收货」这一段链路）。
   缺它订单会永久停在 201，`ship_sn` 与 `confirm_time` 恒为空。

4. **ES 关键词做不到严格隔离。** 搜索是多字段 fuzzy 匹配，`SIM` 前缀的关键词照样会捞到真实商品。
   → 落单隔离只依赖 `goodsIds: [9900001, 9900002]` 与秒杀 `activityId: 990001`，不依赖关键词。这也是 `sim-search.yml` 只用来验证 QPS 精度、不对账的原因。

5. **账号数 ≠ 并发数。** 账号池只是**并行上限**，真实在途并发由 `threads` 决定，QPS 由 λ 令牌桶决定，三者解耦。
   但同一虚拟用户在同一时刻只能有一轮行为在跑——`AccountPool.lease()/release()` 租约是硬要求：不加租约时多个工作线程会同时驱动同一用户，出现「A 线程加购、B 线程下单把购物车消费掉」的自造故障，实测失败率 72%、`cart.delete` 大面积 `BIZ_500`；加租约后同配置 84 轮 100% 通过。

6. **秒杀 Redis 库存必须是裸整数。** `zeroai:seckill:stock:{skuId}` 写入带引号或 JSON 会导致 Lua `tonumber` 得 nil、接口返回 -2（库存不足），现象酷似「卖完了」。
   → `seed-sim-dataset.sh` 从 DB 读 `available_stock` 后用 `redis-cli SET` 直写，保证压前 DB/Redis 一致。

---

## 18. 真机联调记录（2026-10-26）

### 18.1 环境

| 项 | 值 |
|---|---|
| JDK | 21 |
| 服务 | mobile-api `:82` / admin-api `:81` / message-consumer `:85`（均为 `jars/*.jar` 实跑） |
| 中间件 | MySQL `:3307` / Redis `:6380` / RabbitMQ `:5673`+`15673` / ES `:9200`（Colima） |
| 数据 | 3 个 SIM 商品（9900001~9900003）、3 个 SKU（`number=100000`）、1 个秒杀活动（990001）、1 个秒杀 SKU（`available_stock=1000`） |
| ES | 重建后 233 条文档 |

### 18.2 三条链路

| 配置 | 场景 | 目标 | 实测 | 结论 |
|---|---|---|---|---|
| `sim-smoke.yml` | 单用户全链路（概率置 0） | 走完搜索→详情→加购→下单→支付→发货→收货 | 全段 100% | DB 校验：`order_status=401`、`pay_type=99`、`ship_sn=SIM5`、`confirm_time` 有值 |
| `sim-seckill.yml` | 40 人齐射（`mode: burst`） | 秒杀 token→submit→轮询→支付 | 100% 成功，`seckill.submit` P50 **47ms** | 库存恒等式对平 |
| `sim-search.yml` | 纯搜索阶梯 `15s@20 → 15s@60` | QPS 精度 | **19.9 / 60.0** | 到达率误差 <1%，链路 100% |
| `sim-local.yml` | 常规压测 `15s@5 → 30s@15` | QPS 精度 + 全链路 | **4.9 / 14.1**，84 轮 100% | 见 §18.3 对账 |

> 搜索链路的 QPS 之所以能压得比购买链路高一个量级，是因为它不产生 MQ 流量、不落单，不碰到 §17.1 的消费端瓶颈。
>
> §18.3 的对账输出来自 `sim-local.yml` 的另一次复跑（共 56 单），与表中 84 轮那次**不是同一轮**——两次都是 100% 成功、对账全绿，轮数差异只说明这套配置可重复，不代表指标波动。

### 18.3 对账结果（`verify -c conf/sim-local.yml`）

```
[PASS] C2 库存恒等式 sku=9900001：available+locked+sold=1000
[PASS] C1 超卖检查：未发现 sold_stock 超过初始库存
[PASS] C4 商品库存恒等式 product=9900001：number+locked=100000（number=99892,locked=108）
[PASS] C4 商品库存恒等式 product=9900002：number+locked=100000（number=99892,locked=108）
[PASS] C3 订单幂等：未发现 2 秒内重复建单
[INFO] 订单状态分布（压测开始后）: {401=48, 103=8}
汇总：通过 6，失败 0，跳过 0
```

> 「通过 6」= C2 每条 SKU 计 1 + C1 计 1 + C4 每个货品计 1 + C3 计 1 + 状态分布计 1。SKU 与货品数量不同，这个总数不是固定值。

`103` 是「下单未支付超期系统自动取消」，8/56 ≈ 14.3%，与配置的 `leaveUnpaidRate: 0.15` 吻合——说明**未支付超时关单**这条兜底链路也被真实覆盖到了，不是漏测。

C2 的 `available+locked+sold = 1000` 说明秒杀库存没有泄漏也没有凭空多出；C4 的 `number+locked` 恒等于 100000 说明普通商品的冻结与超时回补配对正确——这条断言同时覆盖了「下单冻结」和「未支付关单回补」两个方向，是普通链路唯一的库存正确性依据。

### 18.4 两个本地硬瓶颈（写在这里，避免下次重复踩）

1. **消费端吞吐 ~10 下单 QPS。** 超过必积压，且积压不可自愈（毒消息阻塞单 listener）。压测前先清 `order_direct_queue` / `dl-queue` / `order_delay_queue` / `seckill_order_direct_queue`，再重启 consumer。
2. **轮询窗口必须 ≥ 端到端建单耗时。** 本地 `local_message` 补偿表定时转发，端到端建单常要 3~4 秒，原写死 2 秒（10×200ms）会稳定误判成「订单没落库」。现改为配置项 `orderPoll: {maxAttempts: 30, intervalMs: 500}` = 15 秒。

### 18.5 `payType=99` 的副作用（重要，别当 bug 查）

模拟支付走 `TestPayStrategy`，只做两件事：订单置 201、虚拟销量 +N。它**不经过** `PaymentCallbackSupport`，因此：

- 不验签、不做支付流水幂等、不发后置消息；
- **不触发 `confirmFrozenStockByOrderId`** → `locked_stock` 停在冻结态、`sold_stock` 不增长。

所以 `number + locked` 恒等式仍然成立，但**「下单数 = 已售数」不能作为校验条件**——那是真实支付才有的行为。要在仿真里覆盖真实扣减，得让 `TestPayStrategy` 补一次真实的冻结转已售调用。
