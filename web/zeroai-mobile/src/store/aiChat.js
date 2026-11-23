import { reactive } from 'vue'
import { showDialog, showToast } from 'vant'

import {
  CODE_UNAUTHORIZED,
  clearAiHistory,
  confirmOrderProposal,
  createProposalFromCart,
  fetchAiHistory,
  fetchOrderProposal,
  streamAgentChat
} from '@/api/ai'
import { addDefaultGoodsProduct, changeNumber, updateCart } from '@/api/cart'
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
  add_to_cart: '已加入购物车',
  show_products: '已展示商品',
  propose_order: '已准备下单'
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
  tick: 0,
  /**
   * 待用户勾选商品的购物车弹层。
   * 由后端 cart_select 事件驱动，勾选结果提交后生成待确认提议。
   */
  cartSelect: {
    visible: false,
    submitting: false,
    addressId: null,
    addressText: '',
    userCouponId: null,
    items: [],
    /** 触发弹层的那条消息；关闭后靠它把「重新选择商品」按钮挂回原消息。 */
    messageId: null
  }
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
 * 开发态链路日志。
 *
 * 只在 dev 构建输出：生产环境把整条 SSE 事件打给浏览器毫无意义，还会泄露会话细节。
 * 正文增量默认不打（一次回答几十条），首次增量改打一条「首字到达」，其余事件原样输出，
 * 这样在控制台里能顺着看完「发问 → 思考 → 工具 → 商品卡/弹层 → 正文 → 结束」。
 *
 * @param {string} stage 阶段名
 * @param {*} [detail] 附加信息
 */
function traceLog(stage, detail) {
  if (!import.meta.env.DEV) {
    return
  }
  if (detail === undefined) {
    console.debug(`[ai-chat] ${stage}`)
    return
  }
  console.debug(`[ai-chat] ${stage}`, detail)
}

/**
 * 打字机缓冲：message.id → 尚未渲染的文本。
 *
 * 为什么需要它：后端把一条回答拆成十几个 message_delta，但整段往往在 200~400ms 内到齐，
 * 浏览器一帧就渲染完，用户看到的是「突然出现一大段」，反而以为卡了很久才返回。
 * 这里把增量排进队列，按固定节奏吐出，既保留真实的流式来源（内容仍来自服务端增量），
 * 又让「正在输出」这件事在视觉上成立。
 */
const pendingText = new Map()

/** 打字机定时器；队列空了就停，不常驻。 */
let pacerTimer = null

/** 每个节拍的基础吐字量（约 60fps，≈100 字/秒）。 */
const PACER_STEP = 2

/**
 * 把服务端增量排进打字机队列。
 *
 * @param {object} message 目标助手消息
 * @param {string} text    增量文本
 */
function enqueueText(message, text) {
  if (!text) {
    return
  }
  pendingText.set(message.id, (pendingText.get(message.id) || '') + text)
  ensurePacer()
}

/** 启动打字机节拍器（幂等）。 */
function ensurePacer() {
  if (pacerTimer) {
    return
  }
  pacerTimer = setInterval(flushOnce, 16)
}

/** 单个节拍：每个有积压的消息吐一小段，积压越多单次吐得越多，避免长回答无限落后。 */
function flushOnce() {
  let active = false
  pendingText.forEach((buffer, id) => {
    if (!buffer) {
      pendingText.delete(id)
      return
    }
    const message = aiChatState.messages.find((item) => item.id === id)
    if (!message) {
      // 消息已被清空（用户点了清空会话），直接丢弃积压
      pendingText.delete(id)
      return
    }
    const step = Math.max(PACER_STEP, Math.min(12, Math.ceil(buffer.length / 30)))
    message.content += buffer.slice(0, step)
    pendingText.set(id, buffer.slice(step))
    active = true
  })
  if (active) {
    touch()
    return
  }
  clearInterval(pacerTimer)
  pacerTimer = null
}

/**
 * 立刻把积压文本全部吐出。
 * 用于「停止 / 发送新问题 / 清空」这类需要把上一条收尾的场景，避免新旧答案交错渲染。
 */
function flushAllPending() {
  if (!pendingText.size) {
    return
  }
  pendingText.forEach((buffer, id) => {
    const message = aiChatState.messages.find((item) => item.id === id)
    if (message && buffer) {
      message.content += buffer
    }
  })
  pendingText.clear()
  if (pacerTimer) {
    clearInterval(pacerTimer)
    pacerTimer = null
  }
  touch()
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
    /** 本轮搜索结果对应的商品卡，带「加入购物车」按钮。 */
    products: [],
    /** 本轮弹过购物车勾选层的原始载荷，关掉后仍可一键重开。 */
    cartSheet: null,
    /** 是否已记录过「首字到达」，仅用于控制台链路日志去重。 */
    firstDeltaLogged: false,
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

  // 正文增量太密：只在第一条时记一次，其余事件逐条打出来
  if (name === 'message_delta') {
    if (!message.firstDeltaLogged && data.delta) {
      message.firstDeltaLogged = true
      traceLog('首字到达')
    }
  } else {
    traceLog(`事件 ${name}`, data)
  }

  switch (name) {
    case 'message_delta':
      /*
       * 开始输出正文，说明前面的过程态都结束了。
       * 必须显式收敛：首条 thinking 的 tool 是 other，永远等不到同名 tool_result，
       * 不处理的话页面上会留一个转不停的圈。
       */
      settleRunningSteps(message, 'done')
      // 不直接拼接：交给打字机队列，避免整段回答在一帧内「闪」出来
      enqueueText(message, data.delta || '')
      break
    case 'thinking':
      // 服务端已给出真实进度，本地那条「正在思考」占位就该退场
      message.steps = message.steps.filter((step) => !step.pending)
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
    case 'product_cards':
      applyProductCards(message, data)
      break
    case 'cart_added':
      markProductAdded(data.goodsId)
      showToast('已加入购物车')
      break
    case 'cart_select':
      // 快照留在消息上：用户关掉弹层后还能从消息里重新打开，不必再问一遍 AI
      message.cartSheet = data
      openCartSelection(data, message.id)
      break
    case 'confirm_action':
      markOldProposalsSuperseded(message)
      message.proposal = mapProposalEvent(data)
      break
    case 'degrade':
      message.error = data.message || 'AI 暂时不可用，已切换为普通搜索'
      break
    case 'error':
      message.error = data.message || '服务繁忙，请稍后再试'
      settleRunningSteps(message, 'failed')
      break
    case 'done':
      // 兜底：本轮结束时不该再有转圈的过程态
      settleRunningSteps(message, 'done')
      break
    default:
      break
  }
}

/**
 * 把仍在运行的过程态置为终态。
 *
 * @param {object} message 助手消息
 * @param {'done'|'failed'} state 目标状态
 */
function settleRunningSteps(message, state) {
  message.steps.forEach((step) => {
    if (step.state === 'running') {
      step.state = state
    }
  })
  message.steps = message.steps.filter((step) => !step.pending)
}

/**
 * 合并商品卡。同一 goodsId 只保留一张，最多 12 张，避免长对话把页面撑爆。
 *
 * @param {object} message 助手消息
 * @param {object} data    product_cards 载荷
 */
function applyProductCards(message, data) {
  const incoming = Array.isArray(data.items) ? data.items : []
  if (!incoming.length) {
    return
  }
  const merged = [...message.products]
  incoming.forEach((item) => {
    if (!item || item.goodsId == null) {
      return
    }
    const exists = merged.find((card) => card.goodsId === item.goodsId)
    if (exists) {
      Object.assign(exists, item)
      return
    }
    merged.push({ ...item, added: false, adding: false })
  })
  // 卡片只是「对话内的候选清单」，不是搜索列表页：后端每次最多推 3 条，
  // 这里再兜一层，避免多轮搜索合并后把整屏铺满
  message.products = merged.slice(0, 6)
}

/**
 * 把所有消息里同一商品的卡片标记为已加入购物车。
 *
 * @param {number} goodsId 商品 ID
 */
function markProductAdded(goodsId) {
  aiChatState.messages.forEach((message) => {
    ;(message.products || []).forEach((card) => {
      if (card.goodsId === goodsId) {
        card.added = true
        card.adding = false
      }
    })
  })
  touch()
}

/**
 * 打开购物车勾选弹层（后端已经在服务端按默认地址解析过一次）。
 *
 * @param {object} data cart_select 载荷
 */
function openCartSelection(data, messageId) {
  const items = (Array.isArray(data.items) ? data.items : []).map((item) => ({
    ...item,
    checked: item.checked === true,
    // 记住初始勾选：提交时只需要同步改动过的行，少打一轮接口
    initialChecked: item.checked === true,
    // 数量以服务端快照为准，改动后先调接口再更新本地，失败可回滚
    savingNumber: false
  }))
  aiChatState.cartSelect = {
    visible: true,
    submitting: false,
    addressId: data.addressId || null,
    addressText: data.addressText || '',
    userCouponId: data.userCouponId || null,
    items,
    messageId: messageId || null
  }
  touch()
}

/**
 * 服务端确认卡片 → 前端渲染模型。
 * 金额统一按字符串展示，避免浮点误差。
 *
 * @param {object} data confirm_action 载荷
 * @returns {object} 提议卡片状态
 */
function mapProposalEvent(data) {
  return {
    proposalNo: data.proposalNo,
    actualPrice: data.actualPrice,
    itemCount: data.itemCount,
    expireAt: data.expireAt,
    message: data.message,
    orderSn: '',
    error: '',
    state: 'pending'
  }
}

/**
 * 生成新提议时把之前的待确认卡片标记为「已被替代」。
 * 服务端已经作废旧提议（同一用户只保留一条），这里同步 UI，
 * 否则页面上两张卡片都写着「待确认」，用户点旧那张只会拿到失败。
 *
 * @param {object} current 当前这条消息（保留自己的提议）
 */
function markOldProposalsSuperseded(current) {
  aiChatState.messages.forEach((message) => {
    if (message === current || !message.proposal) {
      return
    }
    if (message.proposal.state === 'pending') {
      message.proposal.state = 'superseded'
      message.proposal.error = '已被新的下单提议替代'
    }
  })
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

  // 上一条若有剩余打字机积压，先一次性放完，避免两条回答的内容交错
  flushAllPending()

  traceLog('发送问题', { length: content.length })
  aiChatState.messages.push({ ...createMessage('user'), content })
  const reply = createMessage('assistant')
  // 本地占位：服务端第一条 thinking 到达前（首字延迟 1~4s），先让用户看到「在思考」
  reply.steps.push({ tool: 'other', message: '正在思考', state: 'running', pending: true })
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
    // 停止或异常导致没有任何内容时，给一个明确的收尾提示，避免出现空气泡。
    // 有商品卡或提议也算「有结果」：模型只调工具、没输出文本是合法情况，
    // 早前只看 content 会把「已展示商品/已加购」误判成空回复
    reply.steps = reply.steps.filter((step) => !step.pending)
    const hasArtifact = Boolean(reply.products.length || reply.proposal || reply.error)
    if (!reply.content && !hasArtifact) {
      reply.error = '本次没有返回内容，请重试'
    }
    traceLog('流结束')
    touch()
  })

  return true
}

/**
 * 用户主动停止：中断订阅，已输出的内容保留。
 */
export function stopStream() {
  if (!controller) {
    return
  }
  controller.abort()
  flushAllPending()
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
  traceLog('确认下单', { proposalNo: proposal.proposalNo })
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
 * 商品卡上的「加入购物车」。
 *
 * 走的是购物车域既有接口（默认货品），与商品详情页加购完全同一条写入路径；
 * 成功后只更新卡片状态，不打扰对话——用户接下来直接说「下单」即可。
 *
 * @param {object} card 商品卡（含 goodsId / adding / added）
 */
export function addProductToCart(card) {
  if (!card || card.added || card.adding) {
    return
  }
  if (!getToken()) {
    redirectToLogin()
    return
  }
  traceLog('加购点击', { goodsId: card.goodsId })
  card.adding = true
  touch()
  addDefaultGoodsProduct({ goodsId: card.goodsId, number: 1 })
    .then(() => {
      card.added = true
      showToast('已加入购物车')
    })
    .catch(() => {
      // 失败提示由请求拦截器统一弹出，这里只复位按钮
    })
    .finally(() => {
      card.adding = false
      touch()
    })
}

/** 关闭购物车勾选弹层（用户主动取消，不改动任何数据）。 */
export function closeCartSelection() {
  aiChatState.cartSelect.visible = false
  touch()
}

/**
 * 从消息里重新打开购物车弹层。
 * <p>
 * 之前的实现关掉就只能再跟 AI 说一次「下单」才会重新弹出，等于把选择权交给了模型。
 * 快照留在消息上，用户点一下即可回到刚才的勾选界面。
 *
 * @param {object} message 触发过 cart_select 的助手消息
 */
export function reopenCartSelection(message) {
  if (!message || !message.cartSheet) {
    return
  }
  traceLog('重新打开购物车弹层', { messageId: message.id })
  openCartSelection(message.cartSheet, message.id)
}

/**
 * 在勾选弹层里修改数量。
 * <p>
 * 必须先落库再提交提议：服务端生成提议时读的是购物车行里的 number，
 * 如果只改前端状态，用户会看到「弹层里 3 件、提议里 1 件」。
 * 本地先乐观更新让 stepper 立即响应，失败再回滚并提示。
 *
 * @param {object} item   购物车行
 * @param {number} number 目标数量
 */
export function changeCartItemNumber(item, number) {
  if (!item || item.savingNumber) {
    return
  }
  const target = Number(number)
  const previous = item.number
  if (!target || target === previous) {
    return
  }
  item.number = target
  item.savingNumber = true
  touch()
  traceLog('改数量', { cartId: item.cartId, number: target })
  changeNumber(item.cartId, target)
    .catch(() => {
      item.number = previous
      showToast('数量修改失败，请重试')
    })
    .finally(() => {
      item.savingNumber = false
      touch()
    })
}

/**
 * 提交购物车勾选结果并生成待确认提议。
 *
 * 两个动作必须按顺序：先把勾选状态写回购物车（服务端只认 {@code checked=true} 的行），
 * 再带着 cartIds 生成提议。任何一步失败都不关闭弹层，用户的勾选不会丢。
 */
export function submitCartSelection() {
  const sheet = aiChatState.cartSelect
  if (sheet.submitting) {
    return
  }
  const selected = sheet.items.filter((item) => item.checked)
  if (!selected.length) {
    showToast('请先勾选要下单的商品')
    return
  }
  traceLog('提交勾选', { cartCount: selected.length, addressId: sheet.addressId })
  sheet.submitting = true
  touch()

  const changed = sheet.items.filter((item) => item.checked !== item.initialChecked)
  const syncTasks = changed.map((item) =>
    updateCart({ id: item.cartId, checked: item.checked }).catch(() => null)
  )

  Promise.all(syncTasks)
    .then(() =>
      createProposalFromCart({
        addressId: sheet.addressId,
        userCouponId: sheet.userCouponId,
        cartIds: selected.map((item) => item.cartId)
      })
    )
    .then((res) => {
      const vo = (res && res.data) || {}
      applyCreatedProposal(vo)
    })
    .catch(() => {
      // 错误文案已由拦截器提示；保持弹层与勾选状态，用户可以直接重试
    })
    .finally(() => {
      sheet.submitting = false
      touch()
    })
}

/**
 * 把「按勾选生成的提议」落成一条新的助手消息。
 *
 * 为什么不复用原来那条消息：提议是用户勾选后的确定性结果，单独成条更符合时间线，
 * 也让「旧提议被替代」的视觉关系更清楚。
 *
 * @param {object} vo 服务端提议视图
 */
function applyCreatedProposal(vo) {
  markOldProposalsSuperseded(null)
  // 提议已生成，来源消息不再提供「重新选择商品」，避免对同一次下单反复弹层
  const source = aiChatState.messages.find((item) => item.id === aiChatState.cartSelect.messageId)
  if (source) {
    source.cartSheet = null
  }
  const reply = createMessage('assistant')
  reply.content = '已按你勾选的商品生成下单提议，确认后才会真正创建订单。'
  reply.proposal = {
    proposalNo: vo.proposalNo,
    actualPrice: vo.actualPrice,
    itemCount: Array.isArray(vo.items) ? vo.items.length : 0,
    expireAt: vo.expireAt,
    message: vo.message,
    orderSn: '',
    error: '',
    state: 'pending'
  }
  aiChatState.messages.push(reply)
  aiChatState.cartSelect.visible = false
  touch()
  traceLog('提议已生成', { proposalNo: vo.proposalNo, actualPrice: vo.actualPrice })
  showToast('下单提议已生成')
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
      pendingText.clear()
      aiChatState.cartSelect.visible = false
      aiChatState.messages.splice(0, aiChatState.messages.length)
      touch()
      clearAiHistory().catch(() => {
        // 删除失败不影响本地已清空的视图，下次进入会重新拉到残留记录
        showToast('服务端历史清理失败，稍后可重试')
      })
    })
    .catch(() => {})
}
