# PHASE-6.3 导购内加购、勾选下单与流式体感

> 范围：`zeroai-ai-starter`（工具与事件）、`zeroai-mobile-api`（组装）、`web/zeroai-mobile`（AI 页面）。
> 前置：`PHASE-6-hitl.md`（提议与确认分离）、`PHASE-6.2-history-es-and-action-enable.md`（历史与开关）。

## 1. 背景

Phase 6 把「生成提议」和「确认下单」拆成了两个入口，安全边界是对的，但实际使用暴露出四个体验问题：

1. **加购必须离开对话**：对话里说要买，用户还得跳去商品页加购、再回来说一遍，链路断在中间。
2. **看起来没有流式**：直连 `curl /ai/chat` 实测首字 2.7s、后续 20 多条 `message_delta` 在 0.3s 内全部到齐。
   流式在协议层是通的，但「首字前完全静默 + 增量到得过快」让用户觉得是一次性返回，甚至以为卡死。
3. **生成提议要求商品已在购物车勾选**：`propose_order` 只认 `checked=true`，用户被赶去购物车页勾选。
4. **可以同时存在多份待确认提议**：两张卡片都写着「待确认」，点旧那张必然失败。

## 2. 范围

- 新增工具 `add_to_cart`（写购物车，不建订单）。
- 新增事件 `product_cards`（此前只有常量，从未下发）、`cart_added`、`cart_select`。
- `propose_order` 语义调整：默认不建提议，改为下发购物车勾选层。
- 新增 `POST /ai/action/proposal`：按勾选结果生成提议。
- 提议状态机新增 `SUPERSEDED`（被新提议替代）。
- 前端：商品卡加购、购物车勾选弹层、思考过程与打字机渲染。
- 非目标：支付、退款、优惠券选择。

## 3. 核心逻辑调整

### 3.1 加购在对话内完成

- `AddToCartTool`（`add_to_cart`）构造期绑定 `userId`，写入走购物车域既有入口
  `ICartService.addDefaultGoodsProduct`，复用上架校验、库存校验与用户维度锁，
  **不复制任何购物车写入逻辑**。
- 加购是低风险可逆动作，因此允许模型在用户明确表达「要这个/加购」时直接调用，不再二次确认；
  订单写入这一环没有放宽——仍然只能由用户点确认按钮触发。
- 卡片由 `ShowProductsTool`（`show_products`）按 **AI 挑中的 goodsId** 下发。
  搜索只负责召回候选（`search_products` 不做任何筛选判断），
  模型按用户的自然语言条件（预算、品牌、用途……）挑选后，用 `show_products` 回显，最多 3 条。
  前端卡片直接带「加入购物车」按钮；加购成功后再下发 `cart_added`，卡片变为「已加入」。

> **为什么不在检索层加筛选参数**：加 `maxPrice` 只是第一个条件，之后还会有品牌、颜色、用途、
> 是否支持某功能……每加一个都要改 VO、ES 查询、端口方法、前端表单，而模型本来就读得懂这些条件。
> 服务端只提供「按 ID 回显」这一个原语（`show_products`），条件判断留在模型侧，
> 新增任何筛选口径都不需要改后端。实测：搜「手机」返回 5 条含地毯/摆件的噪声候选，
> 模型自行剔除后只把小米手机（2999）推成卡片。

### 3.2 勾选商品在对话内完成

```text
用户说「把小米手机下单」
  → list_my_cart                                # 模型先查购物车，挑出对应行
  → propose_order(addressId, cartIds="25")      # 只传要买的那几行
  → ActionProposalService.prepareCartSelection  # 只读：默认地址（没有则留空）+ 这些行
  → SSE cart_select                             # 前端弹层：地址可换、数量可改
  → 用户在弹层里确认收货地址（GET /address/list 单选，或跳 /address/edit 新增）
  → 用户确认（可改数量，逐个 POST /cart/changeNum/{id}/{num} 落库）
  → POST /ai/action/proposal {addressId, cartIds}
  → 提议卡片（显示「寄送至 …」）                  # 仍需点「确认下单」
```

**地址必须由用户确认。** 默认地址只是弹层里的初值，`POST /ai/action/proposal` 的 `addressId`
为空直接拒绝（不退回默认地址），前端在提交前也会拦截并弹出地址选择层：
否则用户会在不知道寄到哪的情况下拿到一张可确认的提议，等于盲签。

提议卡片同时下发 `addressText`（收件人 + 脱敏手机号 + 省市区门牌）：点「确认下单」前能看到寄送地址。
地址选错不必重新跟模型说一遍——卡片上的「修改地址/商品」用消息里的快照重开弹层，
改完生成新提议，旧提议由服务端置为 `SUPERSEDED`。

弹层关掉不算放弃：`cart_select` 的载荷留在消息上，消息内提供「重新选择下单商品」按钮，
点一下即恢复上次的勾选界面，不需要再跟模型说一遍「下单」。

用户一条地址都没有时也照常弹层（`prepareCartSelection` 允许地址为空），
弹层里显示「请选择收货地址」+「新增收货地址」；提交前仍必须选一个。

数量修改走 `/cart/changeNum/{cartId}/{number}`，该接口已补 userId 归属校验：
非本人购物车返回参数错误，且不区分「不存在」与「别人的」，避免被用来探测购物车 ID。

关键不变量**没有变**：服务端仍然只认 `checked=true` 的购物车行
（`ActionProposalService.resolveCarts` 是唯一拦截点）。弹层只是把「在哪里勾选」搬进对话，
不是绕过勾选——前端提交前会先把勾选状态写回购物车。

地址解析顺序：显式 `addressId` → 默认地址（`is_default` 降序）→ 报错引导去新增地址。
`Address.isDefault` 字段的 getter 是 `isDefault()`，MyBatis-Plus 的 lambda 会解析成 `default` 属性
而找不到列，因此这里用字符串列名构造 `QueryWrapper`，代码内有注释说明。

### 3.3 提议唯一

- `ProposalStatus` 新增 `SUPERSEDED`，`ProposalRejectCode` 同步新增文案。
- `createOrderProposal` 在插入新提议**之前**执行
  `supersedePending(userId)`：`WHERE user_id=? AND status='PENDING'`。
  顺序不能反：先插后作废会留下「两条 PENDING 同时可确认」的窗口。
- 只作废 `PENDING`：`CONFIRMING` 可能已经进入下单入口，作废它会掩盖在途订单的真实结果。
- 前端收到新 `confirm_action` 时，把其它消息里的待确认卡片标为「已被新的下单提议替代」。

### 3.4 流式体感与思考过程

- `AiChatController` 在解析引擎后**立即**下发 `thinking: 正在理解你的问题`，把「已收到」提前到 0ms。
- 工具进度沿用 `AgentEventMapper` 的白名单文案（新增 `add_to_cart → 正在加入购物车`）。
- 前端用打字机队列渲染 `message_delta`：约 100 字/秒，积压越多单次吐字越多，
  保证「内容仍来自服务端真实增量」的同时让流式在视觉上成立。
- 首字到达前显示三点呼吸占位，避免空气泡。

## 4. 并发与一致性

- 加购：沿用购物车域用户维度锁；同商品同货品重复加购是「数量累加」，不是幂等操作，这是可感知语义。
- 生成提议：仍按「同一快照 + 尝试序号」探测幂等键；作废旧提议不影响幂等复用（复用分支先于作废返回）。
- 确认：状态迁移全部走条件 UPDATE，未改动。
- 事件下发失败（连接已关闭等）只影响 UI，不影响工具返回值与模型下一轮推理。

## 5. 验证方式

```bash
# 后端
mvn -o -pl zeroai-ai-starter test

# 前端
cd web/zeroai-mobile && pnpm lint && pnpm build:dev
```

手工验证（需 `AI_ENABLED=true` 且已登录）：

1. 「帮我找 3000 元以内的手机」→ 回复中出现商品卡，点「加入购物车」提示成功。
2. 「把这个加入购物车」→ 模型调 `add_to_cart`，步骤条出现「正在加入购物车」→「已加入购物车」。
3. 「帮我把购物车里的商品下单」→ 对话内弹出购物车勾选层；勾选后点「生成下单提议」→ 出现确认卡片。
4. 连续生成两次提议 → 第一张卡片显示「已被新的下单提议替代」；
   `ai_action_proposal` 中该用户 `status='PENDING'` 只应有一条。

## 6. 链路日志（控制台可读）

一轮对话在控制台能按时间顺序看完整条流转，所有行都带 `userId` / `sessionId`（提议链路带 `proposalNo`），
可以只 grep 一个会话号跟完：

```text
AI 对话分发 userId=98 sessionId=u98 engine=agentscope channel=agent questionLength=17 preview="帮我把购物车里的商品下单"
Agent 对话开始 userId=98 sessionId=u98 questionLength=17 preview="帮我把购物车里的商品下单"
Agent 实例就绪 userId=98 sessionId=u98 costMs=42 tools=[search_products, ..., add_to_cart, propose_order]
工具调用开始 userId=98 sessionId=u98 step=1 tool=list_my_cart
工具结果 list_my_cart userId=98 count=2 checkedCount=0
下发事件 type=thinking userId=98 sessionId=u98 tool=list_my_cart message=正在查看购物车
下发事件 type=tool_result userId=98 sessionId=u98 tool=list_my_cart message=查询完成
工具调用开始 userId=98 sessionId=u98 step=3 tool=propose_order
准备下单选择 userId=98 解析地址 addressId=6 requested=null
准备下单选择完成 userId=98 addressId=6 itemCount=2 totalNumber=7
下发购物车勾选层 userId=98 sessionId=u98 addressId=6 itemCount=2 totalNumber=7 checkedInCart=0
下发事件 type=thinking ... tool=propose_order message=正在准备下单
首字到达 userId=98 sessionId=u98 firstTokenMs=4210
回答完成 userId=98 sessionId=u98 steps=3 firstTokenMs=4210 elapsedMs=6890 textChars=86 truncated=false
Agent 通道结束 userId=98 sessionId=u98 signal=onComplete steps=3 elapsedMs=6902
```

约定与取舍：

- **不打正文增量**：一条回答几十条 `message_delta`，逐条打会淹没链路；改为「首字到达」+「回答完成（textChars）」两个锚点。
- **工具入参只打标识**：goodsId / cartId / keyword / addressId，不打手机号、完整地址、密钥。
- **提议链路单独成段**：`生成提议开始 → 解析购物车 → 作废旧提议 → 已生成下单提议`，
  幂等复用与命中历史提议也各有一行，便于区分「新建」和「复用」。
- 前端在 dev 构建下同步输出 `[ai-chat] 事件 xxx` 到浏览器控制台（正文只打首字），生产构建不输出。

## 7. 后续计划

- 弹层内选择优惠券（当前固定不带券）、切换收货地址。
- 商品卡支持调整数量、直接进入详情页。
- 打字机速度做成可配置项（当前为常量 100 字/秒）。
