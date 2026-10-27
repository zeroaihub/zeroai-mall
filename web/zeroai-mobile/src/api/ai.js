import request from '@/utils/request'
import { getToken } from '@/utils/auth'

/** 后端 SSE 端点相对路径，与 zeroai.ai.chat-path 保持一致。 */
const CHAT_PATH = '/ai/chat'

/** 统一响应码：未登录。移动端由 Sa-Token 异常处理器返回。 */
const CODE_UNAUTHORIZED = 401

/**
 * 解析单个 SSE 事件块。
 *
 * 后端格式为 `event:<type>\ndata:<json>`，冒号后没有空格（SseEmitter 的默认写法），
 * 这里同时兼容 `event: xxx` 与 `event:xxx` 两种写法。
 *
 * @param {string} raw 一个完整的事件块（不含分隔空行）
 * @returns {{name: string, payload: object}|null} 解析结果，无法解析时返回 null
 */
function parseEventBlock(raw) {
  const lines = raw.split(/\r?\n/)
  let name = 'message'
  const dataLines = []

  lines.forEach((line) => {
    if (line.startsWith('event:')) {
      name = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  })

  if (!dataLines.length) {
    return null
  }

  try {
    const parsed = JSON.parse(dataLines.join('\n'))
    return { name, payload: parsed }
  } catch (e) {
    return null
  }
}

/**
 * 以流式方式调用 AI 对话接口。
 *
 * 为什么不用 EventSource：`/ai/**` 需要登录态，而 EventSource 不能自定义请求头，
 * 因此改用 fetch + ReadableStream 手动解析 SSE，这样才能带上 Authorization。
 *
 * 本函数不抛业务异常到 UI 层，所有错误通过 `onError` 回调交给页面处理，
 * 便于页面区分「未登录」「服务不可用」「用户主动中断」三类场景。
 *
 * @param {object}   options
 * @param {string}   options.question 用户问题
 * @param {Function} options.onEvent  (name, data, raw) => void，逐条事件回调
 * @param {Function} [options.onError] (error) => void，错误回调，error.code 可能为 401
 * @param {AbortSignal} [options.signal] 中断信号
 * @returns {Promise<void>} 流结束或中断后 resolve
 */
export function streamAgentChat({ question, onEvent, onError, signal }) {
  const url = `${import.meta.env.VUE_APP_BASE_API}${CHAT_PATH}?q=${encodeURIComponent(question)}`
  const token = getToken()

  return fetch(url, {
    method: 'GET',
    headers: {
      Accept: 'text/event-stream',
      Authorization: token || ''
    },
    signal
  })
    .then(async (response) => {
      const contentType = response.headers.get('content-type') || ''

      // 非流式响应：通常是鉴权失败、参数错误或 AI 未开启，body 是统一响应 JSON
      if (!contentType.includes('text/event-stream')) {
        let message = 'AI 服务暂不可用，请稍后再试'
        let code = response.status
        try {
          const body = await response.json()
          message = body.msg || message
          code = body.code || code
        } catch (e) {
          // body 不是 JSON 时保留默认文案
        }
        const error = new Error(message)
        error.code = code
        throw error
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        buffer += decoder.decode(value, { stream: true })

        // 以空行切分事件块，最后一段可能不完整，留在 buffer 里等下一批数据
        const blocks = buffer.split(/\r?\n\r?\n/)
        buffer = blocks.pop() || ''

        blocks.forEach((block) => {
          const parsed = parseEventBlock(block)
          if (parsed) {
            onEvent(parsed.name, parsed.payload)
          }
        })
      }
    })
    .catch((error) => {
      // 主动中断不算错误，交由调用方按「已停止」处理
      if (error.name === 'AbortError') {
        return
      }
      if (typeof onError === 'function') {
        onError(error)
      }
    })
}

export { CODE_UNAUTHORIZED }

/**
 * 拉取当前用户最近的历史对话。
 *
 * 历史由服务端按 userId 隔离，前端不传用户标识，只传条数。
 * 服务端未开启历史或存储不可用时会返回空数组，这里不做特殊处理。
 *
 * @param {number} [limit] 条数，服务端会再封顶一次
 * @returns {Promise<{code: number, data: Array}>} 统一响应
 */
export function fetchAiHistory(limit = 20) {
  return request({
    url: '/ai/history',
    method: 'get',
    params: { limit }
  })
}

/**
 * 清空当前用户的服务端历史记录。
 *
 * @returns {Promise<{code: number, data: number}>} data 为删除条数
 */
export function clearAiHistory() {
  return request({
    url: '/ai/history',
    method: 'delete'
  })
}

/**
 * 确认下单提议。
 *
 * 这是唯一真正会创建订单的接口，必须由用户点击触发；
 * 失败时后端返回 ok=false 且不会产生任何订单。
 *
 * @param {string} proposalNo 提议编号，来自 confirm_action 事件
 * @returns {Promise<{code: number, data: object}>} 统一响应
 */
export function confirmOrderProposal(proposalNo) {
  return request({
    url: `/ai/action/confirm/${encodeURIComponent(proposalNo)}`,
    method: 'post',
    // 下单要经过原入口的校验与消息投递，比普通查询慢，单列一个更宽的超时
    timeout: 15000
  })
}
