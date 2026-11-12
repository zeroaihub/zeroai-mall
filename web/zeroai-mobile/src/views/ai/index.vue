<template>
  <div class="ai-page">
    <nav-bar title="小零 AI 导购">
      <span class="ai-page__clear" @click="handleClear">清空</span>
    </nav-bar>

    <div ref="scrollRef" class="ai-page__body">
      <!-- 欢迎态：首次进入给三句示例，降低用户不知道问什么的门槛 -->
      <div v-if="!messages.length && !loadingHistory" class="ai-welcome">
        <div class="ai-welcome__avatar">零</div>
        <p class="ai-welcome__title">我是小零，零 AI 商城的导购助手</p>
        <p class="ai-welcome__desc">
          可以帮你找商品、查库存、看订单。下单我只会生成提议，必须你点确认才会真正创建订单。
        </p>
        <div class="ai-welcome__chips">
          <span
            v-for="item in suggestions"
            :key="item"
            class="ai-chip"
            @click="handleSend(item)"
            >{{ item }}</span
          >
        </div>
      </div>

      <!-- 历史加载态：避免欢迎页先闪一下再被历史顶掉 -->
      <div v-if="loadingHistory && !messages.length" class="ai-history-loading">
        <van-loading size="18px" vertical>正在加载历史对话</van-loading>
      </div>

      <div
        v-for="message in messages"
        :key="message.id"
        class="ai-msg"
        :class="`ai-msg--${message.role}`"
      >
        <div v-if="message.role === 'assistant'" class="ai-msg__avatar">零</div>

        <div class="ai-msg__main">
          <!-- 过程态：把 Agent 调了哪些工具显式展示出来，而不是只留一个转圈 -->
          <div v-if="message.steps.length" class="ai-steps">
            <span
              v-for="(step, index) in message.steps"
              :key="index"
              class="ai-steps__item"
              :class="{
                'is-running': step.state === 'running',
                'is-failed': step.state === 'failed'
              }"
            >
              <van-loading
                v-if="step.state === 'running'"
                size="12px"
                class="ai-steps__loading"
              />
              <van-icon
                v-else
                :name="step.state === 'failed' ? 'warning-o' : 'success'"
                class="ai-steps__icon"
              />
              {{ step.message }}
            </span>
          </div>

          <div v-if="message.content" class="ai-msg__bubble">
            {{ message.content }}
          </div>

          <!-- 下单提议：Agent 只能生成提议，真正的写入口是这里的确认按钮 -->
          <div v-if="message.proposal" class="ai-proposal">
            <div class="ai-proposal__head">
              <span class="ai-proposal__title">待确认的下单提议</span>
              <span class="ai-proposal__price">￥{{ message.proposal.actualPrice }}</span>
            </div>
            <p class="ai-proposal__desc">
              {{ message.proposal.message || '需要你确认后才会创建订单' }}
            </p>
            <div class="ai-proposal__meta">
              <span>{{ message.proposal.itemCount }} 件商品</span>
              <span v-if="message.proposal.expireAt">
                有效期至 {{ formatExpire(message.proposal.expireAt) }}
              </span>
            </div>
            <div class="ai-proposal__actions">
              <van-button
                v-if="message.proposal.state === 'pending'"
                class="ai-proposal__btn"
                type="primary"
                size="small"
                round
                @click="handleConfirm(message)"
                >确认下单</van-button
              >
              <van-button
                v-else-if="message.proposal.state === 'confirming'"
                class="ai-proposal__btn"
                size="small"
                round
                loading
                loading-text="提交中"
              />
              <span v-else-if="message.proposal.state === 'confirmed'" class="ai-proposal__ok">
                已提交，订单号 {{ message.proposal.orderSn }}
              </span>
              <span v-else class="ai-proposal__fail">
                {{ message.proposal.error || '确认失败，请重新让助手生成提议' }}
              </span>
            </div>
            <p class="ai-proposal__hint">
              只有你点「确认下单」才会创建订单，助手不能替你下单
            </p>
          </div>

          <div v-if="message.error" class="ai-msg__error">
            {{ message.error }}
          </div>
        </div>

        <div v-if="message.role === 'user'" class="ai-msg__avatar ai-msg__avatar--user">
          我
        </div>
      </div>
    </div>

    <div class="ai-inputbar">
      <div class="ai-inputbar__inner">
        <van-field
          v-model="question"
          class="ai-inputbar__field"
          type="textarea"
          rows="1"
          :autosize="{ maxHeight: 96 }"
          :disabled="sending"
          placeholder="问我商品、库存或订单…"
        />
        <van-button
          v-if="!sending"
          class="ai-inputbar__btn"
          type="primary"
          round
          size="small"
          :disabled="!question.trim()"
          @click="handleSend()"
          >发送</van-button
        >
        <van-button
          v-else
          class="ai-inputbar__btn ai-inputbar__btn--stop"
          round
          size="small"
          @click="handleStop"
          >停止</van-button
        >
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, toRefs, watch } from 'vue'

import {
  aiChatState,
  clearSession,
  confirmProposal,
  ensureHistory,
  sendMessage,
  stopStream
} from '@/store/aiChat'

const suggestions = [
  '帮我找 3000 元以内的手机',
  '查一下我的订单',
  '我的购物车有什么',
  '帮我把购物车里的商品下单'
]

const scrollRef = ref(null)
const question = ref('')

/**
 * 对话状态来自模块级单例：页面卸载不会清空消息，也不会中断正在生成的回答，
 * 所以「切走再回来」能接着看到完整结果，而不是一段消失的空气泡。
 */
const { messages, sending, loadingHistory } = toRefs(aiChatState)

/** 滚动到底部，让流式输出的最新内容始终可见。 */
const scrollToBottom = () => {
  nextTick(() => {
    const el = scrollRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

// 后台仍在生成时也要跟着滚：watch 变更计数器，避免对 messages 做深度监听
watch(
  () => aiChatState.tick,
  () => scrollToBottom(),
  { flush: 'post' }
)

/** 把 ISO 时间裁成「MM-DD HH:mm」，移动端气泡放不下完整时间戳。 */
const formatExpire = (value) => {
  if (!value) {
    return ''
  }
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(5, 16) : text
}

/**
 * 发送一条提问。
 *
 * @param {string} [text] 指定文本（欢迎页示例词）；不传则用输入框内容
 */
const handleSend = (text) => {
  const content = (text || question.value || '').trim()
  if (!content || sending.value) {
    return
  }
  // 先清空输入框，避免请求发出后旧文案还留在输入区
  question.value = ''
  sendMessage(content)
}

/** 用户主动停止：中断订阅，已输出的内容保留。 */
const handleStop = () => {
  stopStream()
}

/** 清空会话：本地气泡与服务端历史一起清。 */
const handleClear = () => {
  clearSession()
}

/** 确认下单：走后端确认接口，卡片状态由 store 维护。 */
const handleConfirm = (message) => {
  confirmProposal(message)
}

onMounted(() => {
  ensureHistory()
  scrollToBottom()
})

onBeforeUnmount(() => {
  /*
   * 这里刻意不 abort：离开页面只是「看不见」，流要继续收完，历史也才会落库。
   * 早期版本在这里 abort，导致两个问题——回来时回答消失；服务端收不到 done
   * 而不写历史，重进页面查不到这一轮。
   * 需要中断请走「停止」按钮，需要丢弃请走「清空」。
   */
})
</script>

<style lang="scss" scoped>
.ai-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: var(--wb-surface-page);
  color: var(--wb-text);

  &__clear {
    font-size: 26px;
    color: var(--wb-text-tertiary);
  }

  &__body {
    flex: 1;
    overflow-y: auto;
    padding: 28px 24px 240px;
    -webkit-overflow-scrolling: touch;
  }
}

.ai-welcome {
  padding: 40px 8px 0;

  &__avatar {
    width: 88px;
    height: 88px;
    border-radius: 50%;
    background: linear-gradient(135deg, #0071e3 0%, #42a5ff 100%);
    color: #fff;
    font-size: 36px;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: var(--wb-shadow-card);
  }

  &__title {
    margin: 24px 0 8px;
    font-size: 34px;
    font-weight: 600;
    letter-spacing: -0.24px;
  }

  &__desc {
    margin: 0;
    font-size: 24px;
    line-height: 1.5;
    color: var(--wb-text-secondary);
  }

  &__chips {
    display: flex;
    flex-wrap: wrap;
    gap: 16px;
    margin-top: 32px;
  }
}

.ai-chip {
  padding: 14px 24px;
  border-radius: var(--wb-radius-pill);
  background: var(--wb-surface-elevated);
  border: 1px solid var(--wb-border-strong);
  font-size: 24px;
  color: var(--wb-text-secondary);
}

.ai-history-loading {
  padding: 40px 0;
  text-align: center;
  font-size: 24px;
  color: var(--wb-text-tertiary);
}

/* 下单提议卡片：和普通气泡区分开，强调「这里有个待办动作」 */
.ai-proposal {
  padding: 24px 26px;
  border-radius: var(--wb-radius-soft);
  background: var(--wb-surface-elevated);
  border: 1px solid rgba(0, 113, 227, 0.28);
  box-shadow: var(--wb-shadow-card);

  &__head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 16px;
  }

  &__title {
    font-size: 26px;
    font-weight: 600;
    color: var(--wb-text);
  }

  &__price {
    font-size: 32px;
    font-weight: 600;
    color: #ff3b30;
  }

  &__desc {
    margin: 12px 0 0;
    font-size: 24px;
    line-height: 1.5;
    color: var(--wb-text-secondary);
  }

  &__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 20px;
    margin-top: 12px;
    font-size: 22px;
    color: var(--wb-text-tertiary);
  }

  &__actions {
    margin-top: 20px;
    display: flex;
    align-items: center;
    gap: 16px;
  }

  &__btn {
    min-width: 180px;
    height: 68px;
    font-size: 26px;
  }

  &__ok {
    font-size: 24px;
    color: #34c759;
  }

  &__fail {
    font-size: 24px;
    line-height: 1.5;
    color: #ff3b30;
  }

  &__hint {
    margin: 14px 0 0;
    font-size: 21px;
    line-height: 1.4;
    color: var(--wb-text-tertiary);
  }
}

.ai-msg {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 28px;

  &--user {
    justify-content: flex-end;
  }

  &__avatar {
    flex: none;
    width: 64px;
    height: 64px;
    border-radius: 50%;
    background: linear-gradient(135deg, #0071e3 0%, #42a5ff 100%);
    color: #fff;
    font-size: 26px;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;

    &--user {
      background: var(--wb-surface-dark);
    }
  }

  &__main {
    max-width: 520px;
    display: flex;
    flex-direction: column;
    gap: 12px;

    .ai-msg--user & {
      align-items: flex-end;
    }
  }

  &__bubble {
    padding: 22px 26px;
    border-radius: var(--wb-radius-soft);
    background: var(--wb-surface-elevated);
    box-shadow: var(--wb-shadow-card);
    font-size: 28px;
    line-height: 1.55;
    white-space: pre-wrap;
    word-break: break-word;

    .ai-msg--user & {
      background: var(--wb-blue);
      color: #fff;
      box-shadow: none;
    }
  }

  &__error {
    font-size: 24px;
    line-height: 1.5;
    color: #ff3b30;
  }
}

.ai-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;

  &__item {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 8px 18px;
    border-radius: var(--wb-radius-pill);
    background: rgba(0, 113, 227, 0.08);
    color: var(--wb-blue-link);
    font-size: 22px;

    &.is-running {
      background: rgba(0, 113, 227, 0.12);
    }

    &.is-failed {
      background: rgba(255, 59, 48, 0.1);
      color: #ff3b30;
    }
  }

  &__icon {
    font-size: 22px;
  }
}

.ai-inputbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  padding: 16px 24px calc(16px + env(safe-area-inset-bottom));
  background: var(--wb-surface-elevated);
  border-top: 1px solid var(--wb-border);
  backdrop-filter: blur(24px);

  &__inner {
    display: flex;
    align-items: flex-end;
    gap: 16px;
  }

  &__field {
    flex: 1;
    padding: 16px 24px;
    border-radius: var(--wb-radius-soft);
    background: var(--wb-surface-subtle);
    font-size: 28px;

    :deep(.van-field__control) {
      font-size: 28px;
      line-height: 1.45;
    }
  }

  &__btn {
    flex: none;
    min-width: 128px;
    height: 76px;
    font-size: 26px;

    &--stop {
      background: var(--wb-surface-dark);
      border-color: var(--wb-surface-dark);
      color: #fff;
    }
  }
}
</style>
