import { reactive } from 'vue'
import { showDialog, showToast } from 'vant'

import {
  CODE_UNAUTHORIZED,
  clearAiHistory,
  confirmOrderProposal,
  fetchAiHistory,
  fetchOrderProposal,
  streamAgentChat
} from '@/api/ai'
import { getToken } from '@/utils/auth'
import router from '@/router'

/**
 * AI 对话运行时状态（模块级单例）。
 *
 * 为什么状态不放在页面组件里：对话是「长连接流式输出」，一次回答可能持续十几秒，
 * 用户中途返回上一页、点开别的商品、关掉弹层都属正常操作。早期版本在
 * `onBeforeUnmount` 里 abort 请求，带来两个后果：
 *   1. 回到页面时正在生成的回答整段消失，只剩空气泡；
 *   2. 服务端收不到 `done` 事件，这一轮不会写入对话历史，重进页面也查不到，
 *      表现为「关闭对话框后收不到回复记录」。
 * 状态提升到模块单例后，页面卸载只是「看不见」，流仍在后台继续收，
 * 回到页面直接渲染当前结果。真正的中断只保留两个来源：用户点「停止」、用户清空会话。
 */

/**
 * 工具完成后展示的文案。
 * 后端 tool_result 只给通用「查询完成」，这里按工具名换成更具体的结果态，
 * 让用户看得出「刚才到底查了什么」。未登记的工具回落到后端文案。
 */
const TOOL_DONE_TEXT = {
  search_products: '已检索商品',
  get_product_detail: '已获取商品详情',
  get_stock_status: '已确认库存',
  search_knowledge: '已查商城规则',
  list_my_orders: '已查询订单',
  list_my_addresses: '已核对地址',
  list_my_cart: '已查看购物车',
  propose_order: '已生成下单提议'
}

/**
 * 提议状态与服务端状态字符串的映射。
 * 未知状态按失败处理，避免卡片永远停在「提交中」。
 */
const PROPOSAL_STATE_MAP = {
  PENDING: 'pending',
  CONFIRMING: 'confirming',
  CONFIRMED: 'confirmed',
  REJECTED: 'failed',
  EXPIRED: 'failed'
}

/** 轮询间隔与最大次数：约 12 秒，覆盖正常情况下消息消费的耗时。 */
const PROPOSAL_POLL_INTERVAL_MS = 1200
const PROPOSAL_POLL_MAX_TIMES = 10

/** 单次拉取的历史条数，服务端会再封顶一次。 */
const HISTORY_LIMIT = 20

/**
 * 全局对话状态。
 * `tick` 是变更计数器：页面只需要 watch 它就能在流式输出时持续滚动到底部，
 * 不必对 messages 做深度监听。
 */
export const aiChatState = reactive({
  messages: [],
  sending: false,
  loadingHistory: false,
  /** 是否已尝试过加载历史，避免每次进页面重复请求。 */
  loaded: false,
  tick: 0
})

/** 自增消息 ID，仅用于列表 diff，不参与业务。 */
let messageSeq = 0

/** 当前在途请求的中断句柄；null 表示没有进行中的回答。 */
let controller = null

/** 通知视图有一次状态变更。 */
function touch() {
  aiChatState.tick += 1
}

/**
 * 生成一个消息对象，content 为空表示正在等待首个增量。
 *
 * @param {'user'|'assistant'} role 角色
 * @returns {object} 消息对象
 */
function createMessage(role) {
  messageSeq += 1
  return {
    id: messageSeq,
    role,
    content: '',
    steps: [],
    error: '',
    proposal: null
  }
}

/** 未登录时统一引导到登录页，并带上回跳地址。 */
function redirectToLogin() {
  showDialog({
    title: '需要登录',
    message: 'AI 导购需要登录后才能查看你的订单与购物车',
    showCancelButton: true,
    confirmButtonText: '去登录'
  })
    .then(() => {
      router.push({ path: '/login', query: { redirect: location.href } })
    })
    .catch(() => {})
}

/**
 * 把 SSE 事件写进当前这条助手消息。
 *
 * @param {object} message 助手消息对象
 * @param {string} name    事件名
 * @param {object} payload 事件载荷
 */
function applyEvent(message, name, payload) {
  const data = (payload && payload.data) || {}

  switch (name) {
    case 'message_delta':
      message.content += data.delta || ''
      break
    case 'thinking':
      message.steps.push({
        tool: data.tool,
        message: data.message || '正在处理',
        state: 'running'
      })
      break
    case 'tool_result': {
      // 找到同名且仍在运行的那一步，回填结果状态
      const running = [...message.steps]
        .reverse()
        .find((step) => step.tool === data.tool && step.state === 'running')
      if (running) {
        running.state = data.success === false ? 'failed' : 'done'
        running.message =
          data.success === false
            ? data.message || '查询失败'
            : TOOL_DONE_TEXT[data.tool] || data.message || '已完成'
      }
      break
    }
    case 'confirm_action':
      // 提议只是「待确认」：这里只渲染卡片，真正下单要用户点按钮
      message.proposal = {
        proposalNo: data.proposalNo,
        actualPrice: data.actualPrice,
        itemCount: data.itemCount,
        expireAt: data.expireAt,
        message: data.message,
        orderSn: '',
        error: '',
        state: 'pending'
      }
      break
    case 'degrade':
      message.error = data.message || 'AI 暂时不可用，已切换为普通搜索'
      break
    case 'error':
      message.error = data.message || '服务繁忙，请稍后再试'
      break
    default:
      // done / product_cards 当前页面不额外渲染
      break
  }
}

/**
 * 把服务端返回的历史记录还原成气泡列表。
 * 历史里不保存工具过程态的实时状态，这里统一按「已完成」渲染。
 *
 * @param {Array} items 历史条目（按时间正序）
 * @returns {Array} 消息对象列表
 */
function buildHistoryMessages(items) {
  const restored = []
  items.forEach((item) => {
    if (!item || !item.question) {
      return
    }
    restored.push({ ...createMessage('user'), content: item.question })

    const reply = createMessage('assistant')
    reply.content = item.answer || ''
    reply.steps = (item.tools || []).map((tool) => ({
      tool,
      message: TOOL_DONE_TEXT[tool] || '已完成',
      state: 'done'
    }))
    if (item.success === false) {
      reply.error = item.answer
        ? '这条回答生成时被中断，内容不完整'
        : '这条回答生成时被中断'
    }
    restored.push(reply)
  })
  return restored
}

/**
 * 加载服务端历史（只在首次进入且本地没有内容时执行）。
 *
 * 历史是「补在前面」而不是「覆盖」：拉取期间用户可能已经发了新问题，
 * 直接替换会把刚发出去的气泡冲掉。
 */
export function ensureHistory() {
  if (aiChatState.loaded || aiChatState.loadingHistory) {
    return
  }
  // 未登录不标记 loaded：登录后再进页面应该能拉到自己的历史
  if (!getToken()) {
    return
  }
  aiChatState.loaded = true
  // 已经聊过（含正在生成）就不拉历史，避免把本地气泡换成旧记录
  if (aiChatState.messages.length || aiChatState.sending) {
    return
  }

  aiChatState.loadingHistory = true
  touch()
  fetchAiHistory(HISTORY_LIMIT)
    .then((res) => {
      const items = (res && res.data) || []
      if (!items.length) {
        return
      }
      // 服务端按时间倒序返回，翻正后再渲染，保证「问在上、答在下」
      const restored = buildHistoryMessages([...items].reverse())
      if (restored.length) {
        aiChatState.messages.unshift(...restored)
      }
      touch()
    })
    .catch(() => {
      // 历史是旁路能力：拉取失败不影响正常提问，不打扰用户
    })
    .finally(() => {
      aiChatState.loadingHistory = false
      touch()
    })
}

/**
 * 发送一条提问。
 *
 * 本函数不等待回答结束：流在后台持续把增量写进 aiChatState，
 * 页面切换、组件卸载都不会中断它。
 *
 * @param {string} text 用户问题
 * @returns {boolean} 是否真的发起了请求（重复点击或空内容返回 false）
 */
export function sendMessage(text) {
  if (aiChatState.sending) {
    return false
  }

  const content = (text || '').trim()
  if (!content) {
    return false
  }

  if (!getToken()) {
    redirectToLogin()
    return false
  }

  aiChatState.messages.push({ ...createMessage('user'), content })
  const reply = createMessage('assistant')
  aiChatState.messages.push(reply)
  aiChatState.sending = true
  touch()

  const current = new AbortController()
  controller = current

  streamAgentChat({
    question: content,
    signal: current.signal,
    onEvent: (name, payload) => {
      applyEvent(reply, name, payload)
      touch()
    },
    onError: (error) => {
      if (error.code === CODE_UNAUTHORIZED) {
        redirectToLogin()
        return
      }
      reply.error = error.message || '服务繁忙，请稍后再试'
      touch()
    }
  }).finally(() => {
    // 只有仍是「当前请求」时才复位，避免停止后立刻重发被前一次的收尾覆盖
    if (controller === current) {
      controller = null
      aiChatState.sending = false
    }
    // 停止或异常导致没有任何内容时，给一个明确的收尾提示，避免出现空气泡
    if (!reply.content && !reply.error) {
      reply.error = '本次没有返回内容，请重试'
    }
    touch()
  })

  return true
}

/** 用户主动停止：中断订阅，已输出的内容保留。 */
export function stopStream() {
  if (!controller) {
    return
  }
  controller.abort()
  showToast('已停止')
}

/**
 * 轮询提议状态直到出现最终结果。
 *
 * 服务端在查询提议时会顺带把「处理中」回收成已确认 / 已拒绝 / 超时，
 * 所以这里只读提议，不去查订单，避免两套真相。
 *
 * @param {object} proposal 带 proposalNo 的提议对象
 * @param {number} attempt  已轮询次数
 */
function pollProposalResult(proposal, attempt) {
  if (attempt >= PROPOSAL_POLL_MAX_TIMES) {
    proposal.state = 'failed'
    proposal.error = '订单结果确认中，请到「我的订单」查看是否已生成'
    touch()
    return
  }
  setTimeout(() => {
    fetchOrderProposal(proposal.proposalNo)
      .then((res) => {
        const vo = (res && res.data) || {}
        const state = PROPOSAL_STATE_MAP[vo.status] || 'failed'
        if (state === 'confirming') {
          pollProposalResult(proposal, attempt + 1)
          return
        }
        proposal.state = state
        proposal.orderSn = vo.orderSn || proposal.orderSn || ''
        if (state === 'failed') {
          proposal.error = vo.message || '确认失败，请重新生成提议'
        } else {
          showToast('下单成功')
        }
        touch()
      })
      .catch(() => {
        // 单次查询失败不终止轮询，网络抖动不代表下单失败
        pollProposalResult(proposal, attempt + 1)
      })
  }, PROPOSAL_POLL_INTERVAL_MS)
}

/**
 * 确认下单。
 *
 * 这是用户显式动作，也是唯一会创建订单的入口；
 * 失败时后端保证没有产生订单，这里只把原因显示在卡片上，不重试。
 *
 * 后端下单是异步的：确认接口返回 processing=true 说明消息已入队、订单可能还没落库，
 * 此卡片必须保持「提交中」并轮询提议状态，拿到落库结果后再给结论——
 * 早期版本直接显示「已提交」，消费端没启动时用户会看到一个查不到的订单号。
 *
 * @param {object} message 带 proposal 的助手消息
 */
export function confirmProposal(message) {
  const proposal = message && message.proposal
  if (!proposal || proposal.state !== 'pending') {
    return
  }
  proposal.state = 'confirming'
  touch()

  confirmOrderProposal(proposal.proposalNo)
    .then((res) => {
      const result = (res && res.data) || {}
      if (!result.ok) {
        proposal.state = 'failed'
        proposal.error = result.message || '确认失败，请重新生成提议'
        return
      }
      proposal.orderSn = result.orderSn || ''
      if (result.processing) {
        // 订单还没落库：保持提交中，继续问服务端要最终结果
        pollProposalResult(proposal, 0)
        return
      }
      proposal.state = 'confirmed'
      showToast('下单成功')
    })
    .catch((error) => {
      proposal.state = 'failed'
      proposal.error = error.message || '确认失败，请稍后再试'
    })
    .finally(() => {
      touch()
    })
}

/**
 * 清空会话。
 *
 * 服务端历史与会话状态是两回事：这里清掉的是「可回看的历史记录」，
 * 助手自身的多轮上下文仍由服务端会话维持，所以先跟用户说清楚再删。
 */
export function clearSession() {
  if (!aiChatState.messages.length) {
    return
  }
  showDialog({
    title: '清空对话',
    message: '将同时删除服务端保存的历史记录，删除后无法恢复',
    showCancelButton: true,
    confirmButtonText: '清空'
  })
    .then(() => {
      if (controller) {
        controller.abort()
      }
      aiChatState.messages.splice(0, aiChatState.messages.length)
      touch()
      clearAiHistory().catch(() => {
        // 删除失败不影响本地已清空的视图，下次进入会重新拉到残留记录
        showToast('服务端历史清理失败，稍后可重试')
      })
    })
    .catch(() => {})
}
