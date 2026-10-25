# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

zeroai-mall 是一个 Spring Boot 4.1.2 / JDK 21 的开源 H5 商城后端（AI 能力基于 Spring AI 2.0.2），包含管理端 API、移动端 API、消息消费服务三个可独立部署的应用。后台管理端保留 Spring Security + JWT，移动端已切换为 Sa-Token。

## 常用命令

```bash
# 构建（跳过测试），jar 输出到 ./jars/
mvn clean package -DskipTests

# 运行所有测试
mvn test

# 按领域模块运行测试
mvn test -pl zeroai-domain-trade
mvn test -pl zeroai-domain-inventory

# 运行单个测试类
mvn test -pl zeroai-domain-trade -Dtest=PaymentCallbackSupportTest

# 运行单个测试方法
mvn test -pl zeroai-domain-trade -Dtest=PaymentCallbackSupportTest#markOrderPaid_alreadyPaid_skipsUpdate
```

## 本地启动

**前置中间件**：MySQL 8.0、Redis 3.0+、RabbitMQ 3.0+（需 delayed-message 插件）、Elasticsearch 7.x（需 IK 分词器和拼音插件）

1. 在 `zeroai_mall` 数据库中导入项目根目录的 `zeroai_mall_*.sql`
2. 编辑各模块的 `application-dev.yml`，填写本地中间件连接信息
3. 分别启动三个 Spring Boot 主类：
   - `AdminApplication` → 端口 81
   - `MobileApplication` → 端口 82
   - `MessageApplication` → 端口 85

**Docker 部署**：
```bash
# 中间件
docker-compose -f docker-compose-es7-rabbitmq-redis-mysql.yml up -d
# 应用
docker-compose up -d
```

## AI 协作准则

本仓库的 Claude 协作规则吸收 `andrej-karpathy-skills` 的四个原则：编码前思考、简洁优先、精准修改、目标驱动验证。处理非平凡任务时，先说明假设、歧义点、影响范围和验证方式；如果需求可能有多种解释，不要静默选择复杂方案，应先收敛边界或提出更简单方案。

默认做最小可维护改动，不为单次需求新增通用框架、过度抽象或未要求的配置能力。每一行 diff 都应能追溯到用户目标；不要顺手重排无关代码、删除既有注释、重构未涉及链路或清理用户已有改动。发现无关坏味道时，先在反馈中说明，除非用户明确要求，否则不要纳入本次修改。

订单、库存、支付、秒杀、MQ、本地消息、认证等核心链路必须转成可验证目标：先读取现有实现和文档，明确不变量，再按模块边界小步修改，最后用对应模块测试、编译或文档 diff 自检闭环。文档更新同样需要对照代码，发现版本、认证模式、模块职责等描述与现状不一致时，应在本次相关文档中一并修正。

## 模块结构

| 模块 | 职责 |
|---|---|
| `zeroai-common` | 通用配置、切面、策略接口、通用模型和基础设施 |
| `zeroai-domain-api` | 跨领域契约：entity / Mapper / Service 接口 / VO / 枚举 |
| `zeroai-domain-trade` | 订单、支付编排、状态机、本地消息、对账 |
| `zeroai-domain-inventory` | 库存冻结、库存流水、Redis 库存快照、库存对账 |
| `zeroai-domain-goods` | 商品、SKU、类目、搜索和 ES 同步 |
| `zeroai-domain-cart` | 购物车读写与选中商品聚合 |
| `zeroai-domain-promotion` | 优惠券、营销位和 Diamond 策略实现 |
| `zeroai-payment-channel` | 微信、支付宝、易支付的支付 / 退款渠道适配 |
| `zeroai-admin-api` | 管理端 REST 控制器 + Spring Security 配置 + Spring `@Scheduled` 治理任务 |
| `zeroai-mobile-api` | H5 移动端 REST 控制器 |
| `zeroai-message` | RabbitMQ 消息消费（core + consumer 子模块） |
| `zeroai-data` | Redis / Elasticsearch 基础设施封装 |
| `zeroai-util` | 常量、枚举、异常、工具类 |

## 核心架构

### 业务领域（按 `zeroai-domain-*` 拆分）

核心业务逻辑通过 ServiceImpl + Support 类组织，而非直接写在 Controller 中：

```
zeroai-domain-trade/src/main/java/com/zeroai/domain/trade/support/order/        # 订单提交、取消、生命周期
zeroai-domain-trade/src/main/java/com/zeroai/domain/trade/support/admin/order/  # 管理端发货、退款
zeroai-domain-trade/src/main/java/com/zeroai/domain/trade/support/payment/      # 支付回调幂等处理
zeroai-domain-trade/src/main/java/com/zeroai/domain/trade/outbox/               # 本地消息表（outbox 模式）中继与补偿
zeroai-domain-trade/src/main/java/com/zeroai/domain/trade/support/seckill/      # 秒杀异步落单、超时释放、支付确认
zeroai-domain-inventory/src/main/java/com/zeroai/domain/inventory/support/      # 库存冻结、Redis 预扣、库存快照
zeroai-domain-goods/src/main/java/com/zeroai/domain/goods/support/              # 商品 ES 同步、变更
zeroai-domain-cart/src/main/java/com/zeroai/domain/cart/support/                # 购物车读写
zeroai-domain-promotion/src/main/java/com/zeroai/domain/promotion/support/      # 优惠券领取
zeroai-domain-promotion/src/main/java/com/zeroai/domain/promotion/support/seckill/ # 秒杀活动、短期 token、漏斗过滤
```

### 关键设计模式

**订单提交链**：`OrderSubmitStockReduceStep` 等实现责任链，Redis 预扣库存作为并发门控，MySQL 为最终数据源，`finally` 块保证 Redis 释放。

**支付回调幂等**：`PaymentCallbackSupport` 使用条件更新（`applyExpectedStatus`）+ 二次查询区分"已被其他线程处理"与"真实失败"。`PaymentFlowSaveResult.DUPLICATE_CONFLICT` 通过抛出 `IllegalStateException` 触发 `TransactionTemplate` 回滚。

**本地消息表（outbox）**：`LocalMessageService` 以 `DuplicateKeyException` 实现幂等插入，指数退避重试（最多 5 次），超限后转 `FAILED` 死信。

**秒杀漏斗模型**：秒杀下单先走活动状态、短期 token、用户频控、SKU 限流和 Redis Lua 库存原子扣减，再通过本地消息可靠投递异步落单。订单仍统一写入 `shop_order` / `shop_order_goods`，活动上下文通过 `shop_order_activity_relation` 关联。

**状态机条件更新**：所有订单状态变更统一使用 `applyExpectedStatus(updateWrapper, sourceStatus)` 做乐观并发控制，`updated == 0` 时抛 `BusinessException`。

**Redis Lua 脚本**：`RedisCache` 中的库存预扣（`luaReserveStock`）和释放（`luaReleaseReservedStock`）均为原子 Lua 脚本，返回值语义：`1`=成功/幂等，`-1`=库存不足，`-2`=未初始化，`-3`=参数非法。

### 数据访问

- ORM：MyBatis-Plus，逻辑删除字段 `del_flag`（1=已删除）
- Mapper XML：`classpath*:mapper/**/*Mapper.xml`
- Redis：通过 `RedisCache` 统一封装，避免直接使用 `RedisTemplate`

### 认证

- 管理端（端口 81）：JWT，有效期 120 分钟，header `Authorization`
- 移动端（端口 82）：Sa-Token，有效期 30 天，header `Authorization`
- 鉴权边界：后台继续使用 Spring Security + `@PreAuthorize`，移动端不要重新引入 Spring Security 过滤链，业务代码统一通过 `MobileSecurityUtils` 读取登录用户

### 定时任务

Spring `@Scheduled` 调度，运行在 `AdminApplication` 进程内：

- 入口类：`zeroai-admin-api` → `com.zeroai.admin.api.schedule.TradeGovernanceScheduledTask`
- 配置类：`com.zeroai.admin.api.schedule.TradeScheduleProperties`，前缀 `zeroai.schedule.trade.*`
- 业务侧 relay：`LocalMessageRelayTask`（`zeroai-domain-trade`，每 5 秒一次）
- 失败处理由 Spring 默认 `TaskUtils.LoggingErrorHandler` 兜底，任务方法不需要 try/catch

### 治理接口

后台交易治理 REST 接口位于 `TradeOpsController`，路径前缀 `/ops/trade`，涵盖本地消息查询/重投、库存对账、Redis 库存快照刷新和支付日终对账。
