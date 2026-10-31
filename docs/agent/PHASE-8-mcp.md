# Phase 8：MCP 工具化

> 上游方案：`docs/zeroai-mall-AgentScope-Workbuddy-实施方案.md` Phase 8。
> 一句话结论：MCP Server 已落地为**默认关闭、独立鉴权、只读白名单**的 Streamable HTTP 入口；
> 它复用现有商城工具，不替代内部 Java Service，也没有获得任何下单、支付或库存写能力。

## 一、边界：只暴露 4 个只读工具

| 工具 | 参数 | 输出 | 数据来源 |
|---|---|---|---|
| `search_products` | `keyword` 必填；`limit` 1-10 | 商品 ID、名称、价格、销量 | 现有 `MallSearchPort` / H5 搜索链路 |
| `get_product_detail` | `goodsId` 正整数 | 名称、价格、简介、最多 5 个 SKU、可售库存 | `IGoodsDetailService` |
| `search_knowledge` | `query` 必填；`limit` 1-10 | 公开政策片段、来源、版本、更新时间 | P7 `KnowledgeService` |
| `list_my_orders` | `limit` 1-5 | 当前用户订单号、金额、状态、商品摘要 | `IMobileOrderService` |

永久排除：

- `get_stock_status`：现有工具会返回 `lockedStock` 等内部字段，不适合跨进程暴露。
- `propose_order`、下单、支付、退款、取消、改地址、扣库存：MCP v1 不注册任何写工具。
- AgentScope 的其他能力：MCP 不自动扫描工具 Bean，只从固定的 `McpToolCallbacks` 白名单创建回调。

`ToolCallbackProvider` 使用显式 JSON Schema 和手工参数解析，而不是
`MethodToolCallbackProvider`。原因是 AgentScope 2.0.4 依赖 victools 4.38，
Spring AI 2.0.2 的反射 Schema 生成依赖 victools 5.0；强行升级会破坏 AgentScope。

## 二、身份模型：token 绑定用户，工具参数没有 userId

token 格式：

```text
v1.<userId>.<clientId>.<issuedAt>.<expiresAt>.<signature>
```

- `signature = base64url(HMAC-SHA256(secret, "v1.userId.clientId.issuedAt.expiresAt"))`。
- `MCP_TOKEN_SECRET` 至少 32 个 UTF-8 字节，不能复用登录 token、模型 Key 或支付密钥。
- 默认最长有效期 1 小时，校验允许 60 秒时钟偏差。
- `userId + clientId + issuedAt + expiresAt` 都在签名覆盖范围内，修改任意一段都会失败。
- 工具方法不接收 `userId`；HTTP 过滤器验签后把 `McpPrincipal` 写入 request attribute，
  transport context extractor 再把它注入每次 MCP 工具调用。
- `list_my_orders` 只使用 context 中的用户身份。额外传入 `userId` 会被 JSON Schema 和手工参数校验拒绝。
- `Authorization` 同时兼容 `Bearer <token>` 与移动端全局过滤器归一化后的裸 token；
  `Basic ...` 等其他认证方案不会通过。

token 签发目前是离线运维动作，不暴露 HTTP 接口。本地脚本：

```powershell
$env:MCP_TOKEN_SECRET = '<at-least-32-bytes>'
.\scripts\mcp\issue-token.ps1 -UserId 93 -ClientId cursor-local -TtlSeconds 900
```

生产环境应把签发逻辑放在受控内部服务或运维系统中，密钥只存在于 MCP Server 和签发方。

## 三、接入边界与网络模型

- 协议：MCP Streamable HTTP，端点默认 `/mcp`。
- 服务端：Spring AI MCP Server WebMVC，同步工具模式，`request-timeout=10s`。
- 默认只允许 socket 远端地址为 `127.0.0.1` / `::1`。
- `MCP_ALLOW_REMOTE=false` 是默认值；跨服务接入必须先加 TLS、网络 ACL、审计和独立密钥。
- 不要把 `/mcp` 直接挂到公网 Nginx。若反向代理与 Java 进程同机，Java 看到的远端地址可能是回环地址，
  仅靠 `allow-remote=false` 不能替代代理层的访问控制。
- MCP 开关与 `AI_ENABLED` 独立。模型对话关闭时仍可提供只读 MCP；知识检索实现依赖
  `AI_KNOWLEDGE_ENABLED`，未接入时 `search_knowledge` 返回 `UNAVAILABLE`，不会编造政策。

配置：

```yaml
spring:
  ai:
    mcp:
      server:
        enabled: ${MCP_ENABLED:false}

zeroai:
  ai:
    mcp:
      enabled: ${MCP_ENABLED:false}
      token-secret: ${MCP_TOKEN_SECRET:}
      token-header: Authorization
      token-prefix: Bearer
      max-token-ttl: 1h
      clock-skew: 60s
      allow-remote: ${MCP_ALLOW_REMOTE:false}
```

`spring.ai.mcp.server.enabled` 同时由 `MCP_ENABLED` 控制，避免“入口开关开了但 SDK Server 没开”的半配置状态。

## 四、Schema、分页、错误与版本

固定 Schema 位于 `McpToolCallbacks`，全部 `additionalProperties=false`。服务端还会再次检查：

- 未声明字段直接拒绝；
- 整数必须可转换为目标类型，商品 ID 必须大于 0；
- `limit` 必须在 Schema 声明的范围内，不依赖底层工具静默截断；
- 缺失必填字段或类型错误不会进入业务服务。

分页是单页小结果，不提供游标：

| 工具 | 缺省 | 最大 |
|---|:--:|:--:|
| 商品搜索 | 5 | 10 |
| 知识检索 | 3 | 10 |
| 我的订单 | 3 | 5 |

稳定错误：

| 错误 | 场景 |
|---|---|
| `MCP_UNAUTHORIZED` | token 缺失、格式错误、签名错误或过期 |
| `MCP_REMOTE_FORBIDDEN` | 非回环地址且未显式允许远程 |
| `INVALID_ARGUMENTS` | 手工解析阶段的参数错误 |
| `AUTH_REQUIRED` | 工具调用上下文没有验签身份 |
| `MCP_TOOL_UNAVAILABLE` | 工具运行期异常，响应不返回堆栈 |

MCP 框架还会在调用前按 Schema 校验，错误文本可能来自标准 validator；它同样不会进入业务查询。

版本策略：

- `v1` 内不改已有工具名和参数语义；可以增加可选字段。
- 破坏性变更必须使用新工具名，并保留旧名过渡期。
- `serverInfo.version` 当前为 `1.0.0`，协议协商实测为 `2025-03-26`。

## 五、超时与故障降级

- MCP Server 请求超时：10 秒。
- 知识检索 ES 查询自身超时：2 秒。
- 商品搜索和订单查询继续使用对应领域服务及数据库/ES 客户端超时。
- 当前没有为每个只读工具再包一层独立线程超时；MCP 整体请求超时是最终边界。
- MCP 关闭或初始化失败不影响 `/home/**` 等商城原生接口。
- 工具内部业务异常收敛为稳定 JSON，不把 SQL、ES 地址、堆栈或 token 返回给客户端。
- 审计日志只记录工具名、客户端 ID、用户 ID、耗时和状态，不记录参数、返回值、订单内容或 token。

## 六、本地联调

1. 启动 MySQL、Redis、Elasticsearch、RabbitMQ。
2. 设置一个至少 32 字节的 `MCP_TOKEN_SECRET`。
3. 启动移动端时设置 `MCP_ENABLED=true`。

两个不依赖第三方 SDK 的 smoke client：

```powershell
$env:MCP_TOKEN_SECRET = '<at-least-32-bytes>'
.\scripts\mcp\smoke.ps1 -UserId 1 -ClientId powershell-smoke

python .\scripts\mcp\smoke_mcp.py --secret $env:MCP_TOKEN_SECRET `
  --user-id 1 --client-id python-smoke
```

脚本覆盖：

- Streamable HTTP `initialize`、`notifications/initialized`、`tools/list`、`tools/call`；
- 只发现 4 个允许工具；
- `search_products` 与 `list_my_orders` 正常调用；
- 工具参数额外传 `userId` 被拒绝；
- 无 token 初始化返回 HTTP 401。

## 七、实际验证结果

验证日期：2026-10-31；环境：Windows、Java 21、本地 MySQL / Redis / Elasticsearch / RabbitMQ。

| 验证 | 结果 |
|---|---|
| MCP 单元测试 | 17 个测试通过，0 失败 |
| 移动端及依赖模块全量测试 | `mvn -pl zeroai-mobile-api -am test` BUILD SUCCESS |
| MCP 开启启动 | 日志出现 `Registered tools: 4`，且工具名正确 |
| PowerShell client | `protocol=2025-03-26 tools=4 session=true authRejected=401` |
| Python stdlib client | `protocol=2025-03-26 tools=4 session=true authRejected=401` |
| 模型关闭、MCP 开启 | MCP 可用，证明两者开关解耦 |
| MCP 关闭 | 启动日志无 `MCP Server 已启用` / `Registered tools`，`/home/index` 返回业务 200 |

兼容性说明：

- 本次实际跑通的是两个独立实现的最小 MCP Streamable HTTP client，不是 MCP Inspector、Cursor
  或 Claude Desktop 的官方实测。
- 不建议把“两个 smoke client 通过”扩张解释为“所有第三方 MCP Client 已验证”。
- MCP Inspector / Cursor 接入应使用相同的 `/mcp` 与 Authorization header；若遇到 SSE、session header
  或 localhost 访问差异，应记录客户端版本和原始握手日志后再调整，不能修改鉴权边界迁就客户端。

## 八、回滚

最快回滚：

```env
MCP_ENABLED=false
```

效果：

- 不注册 MCP transport provider、认证过滤器和工具回调；
- `/mcp` 恢复为未映射路径；
- 商城 API、AI 对话和 Knowledge 工具不受影响；
- 可进一步移除 `spring-ai-starter-mcp-server-webmvc` 依赖，但正常回滚不需要改代码。

## 九、限制与后续

- 没有 OAuth / 动态客户端注册；当前只适合受控本地工具或内部服务。
- 没有生产级 token 签发、轮换、吊销列表和审计存储；依赖外部受控签发与短 TTL 缩小风险。
- 没有资源、Prompt、Completion 能力，只启用 tools。
- 没有跨进程限流、用户配额和成本计费；MCP 只读本身不调用模型，但商品/知识检索仍消耗后端资源。
- 没有官方 Inspector / Cursor 自动化兼容矩阵。
- 不为 MCP 复制任何业务逻辑；新增工具必须先证明现有只读 Service 可复用，再进入固定白名单。
