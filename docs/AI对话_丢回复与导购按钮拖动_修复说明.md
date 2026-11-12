# AI 对话「关闭页面丢回复」与「导购按钮不可拖动」修复说明

日期：2026-11-12

## 一、回复生成中关闭对话框，回来收不到记录

### 现象

在 AI 导购页提问，回答还在逐字输出时返回上一页（或关闭页面），再回到 AI 页：
这一轮问答完全消失，历史里也查不到，像是「根本问过」。

### 根因（前后端各一层）

1. **前端把「看不见」当成了「不要了」**
   `views/ai/index.vue` 在 `onBeforeUnmount` 里 `controller.abort()`。
   页面一卸载，SSE 连接立刻被掐断，正在生成的回答直接丢弃；
   消息状态保存在组件内部的 `reactive` 里，组件销毁即清空。

2. **后端把「断连」当成了「没发生过」**
   `AgentChatHandler` 的历史记录只在两条路径收尾：流正常结束（`onComplete`）
   或流异常（`onError`）。客户端断连时走的是 `sink.onClose`，那里只做
   「取消订阅 + 中断 Agent」，从未调用 `turn.success/fail`，于是这一轮永远不会落库。
   代码里还留着当时的注释：「中途断连不会走到 done/error，因此不会落库」——
   设计上是为了「不把半句话当完整回答存进去」，但代价是整轮记录消失。

### 修复

**前端：把对话状态从页面生命周期里摘出来**

新增 `src/store/aiChat.js`（模块级单例，不注册为 vuex module）：

- `aiChatState`：`messages / sending / loadingHistory / loaded / tick`
- `sendMessage / stopStream / clearSession / confirmProposal / ensureHistory`

`views/ai/index.vue` 只保留渲染与滚动，`onBeforeUnmount` **不再 abort**，
并写明原因。中断只保留两个显式入口：用户点「停止」、用户「清空」。

效果：离开页面只是「看不见」，流仍在后台继续接收并写入单例。
回到页面直接渲染当前结果；即使回答恰好在后台跑完，回来后也是完整的。

`tick` 是变更计数器，页面 `watch` 它来滚到底部，避免深度监听 `messages`。

**后端：断连也要收尾落库**

- `AgentChatHandler`：`sink.onClose` 里新增 `turn.fail("CLIENT_ABORT", steps.get())`，
  顺序为「先存档 → 再取消订阅 → 最后中断 Agent」。
- `AiChatController.streamWithChatClient`（单轮通道）：同样补上 `onClose`，
  并保存 `Disposable` 以便真正停止生成（原来只断连不取消，模型会继续跑完）。
- `Turn.finish` 内部 CAS 幂等，因此「正常完成」与「断连」竞争时不会重复写；
  成功路径先 `success` 再 `complete`，`onClose` 里的 `fail` 会被忽略。

前端对 `success === false` 的历史条目会带一条提示：
有部分内容时显示「这条回答生成时被中断，内容不完整」，无内容时显示「这条回答生成时被中断」。

### 实测

模拟「客户端断开」（`curl --max-time 5` 拉长回答，中途掐断）：

| 场景 | 结果 |
|---|---|
| 修复前 | 历史无任何记录 |
| 修复后 | 新记录 `success=false`，保留问题原文与中断前已生成的 6 个字符 |
| 正常跑完 | `success=true`，回答完整（251 字符） |

后端单测 `AgentChatHandlerTest`（7 个）+ `ActionProposalServiceTest`（29 个）全绿。

## 二、AI 导购悬浮按钮不可拖动，挡住操作按钮

### 现象

`components/AiEntry/index.vue` 只有 `position: fixed; right: 24px; bottom: +150px`
一个固定位置，无法移动，压住页面右下的操作按钮。

### 修复

改为可拖拽悬浮球（Pointer Events，统一处理鼠标 / 触摸 / 触控笔）：

- **拖动判定**：位移超过 6px 才算拖动，否则按点击处理，避免手抖点不开
- **不误跳转**：拖动结束后的 click 被抑制，并用 400ms 定时器兜底重置标记
  （某些浏览器在 `preventDefault` 后不再派发 click，标记残留会吞掉下一次正常点击）
- **位置约束**：始终收敛在可视区域内，顶部留 60px、底部留 110px（tabbar + 安全区）
- **贴边吸附**：松手后水平吸附到最近的左 / 右边缘，垂直位置保持不变；
  贴边有 0.2s 过渡，拖动过程中无延迟跟随
- **位置记忆**：写入 `localStorage`（`zeroai.aiEntry.position`），下次进入沿用；
  隐私模式写入失败不影响使用
- **屏幕适配**：`resize`（横竖屏、软键盘）后重新收敛
- **手势隔离**：`touch-action: none` + `user-select: none`，拖动时不滚动页面、不选中文字

### 待观察

「底部预留 110px」是按当前 tabbar 高度估的。若后续 tabbar 改版或新增底部操作条，
需要同步调整 `BOTTOM_RESERVED`。

## 三、验证方式

- 前端：`pnpm build:dev` 通过；dev server（4949）热更新后刷新页面即可
- 后端：`zeroai-mobile-api` 已用新 jar 重启（82）
- 建议手动回归：
  1. 提问 → 回答输出到一半时点返回 → 再点「AI 导购」→ 回答应完整存在
  2. 提问后立刻彻底关闭浏览器 → 重开进入 AI 页 → 应看到该问题与「被中断」提示
  3. 拖动悬浮球到左上角 → 松手贴边 → 刷新页面位置保持 → 单击仍能进入对话页
