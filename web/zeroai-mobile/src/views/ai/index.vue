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
            <span class="ai-steps__label">思考过程</span>
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

          <!-- 首字未到时的占位：避免用户以为请求卡死 -->
          <div
            v-else-if="sending && !message.proposal && !message.error && !message.products.length"
            class="ai-msg__bubble ai-msg__bubble--pending"
          >
            <span class="ai-typing"><i></i><i></i><i></i></span>
          </div>

          <!-- 商品卡：对话内直接加购，不必跳到商品页再回来 -->
          <div v-if="message.products.length" class="ai-products">
            <div v-for="card in message.products" :key="card.goodsId" class="ai-product">
              <div class="ai-product__thumb">
                <img v-if="card.picUrl" :src="card.picUrl" :alt="card.name" />
                <span v-else class="ai-product__placeholder">零</span>
              </div>
              <div class="ai-product__body">
                <p class="ai-product__name">{{ card.name }}</p>
                <div class="ai-product__meta">
                  <span class="ai-product__price">￥{{ card.price }}</span>
                  <span class="ai-product__sales">已售 {{ card.sales || 0 }}</span>
                </div>
              </div>
              <van-button
                class="ai-product__btn"
                size="mini"
                round
                :type="card.added ? 'default' : 'primary'"
                :loading="card.adding"
                :disabled="card.added"
                @click="handleAddCart(card)"
                >{{ card.added ? '已加入' : '加入购物车' }}</van-button
              >
            </div>
          </div>

          <!-- 弹层关掉后仍可重开：不必再跟 AI 说一次「下单」 -->
          <div v-if="message.cartSheet && !message.proposal && !cartSelect.visible" class="ai-cart-reopen">
            <van-button size="small" round plain type="primary" @click="handleReopenCart(message)">
              重新选择下单商品
            </van-button>
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
            <!-- 寄送地址是确认决策的一部分：不显示地址等于让用户盲签 -->
            <p
              v-if="message.proposal.addressText"
              class="ai-proposal__addr"
            >
              <van-icon name="location-o" />
              <span>寄送至 {{ message.proposal.addressText }}</span>
            </p>
            <p v-else-if="message.proposal.state === 'pending'" class="ai-proposal__addr is-warn">
              <van-icon name="warning-o" />
              <span>收货地址已失效，请点「修改地址」重新选择</span>
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
              <!-- 地址选错不用重说一遍：重开弹层改完会生成新提议，旧的自动作废 -->
              <van-button
                v-if="message.proposal.state === 'pending' && message.cartSheet"
                class="ai-proposal__btn"
                size="small"
                round
                plain
                @click="handleReopenCart(message)"
                >修改地址/商品</van-button
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
              <span
                v-else-if="message.proposal.state === 'superseded'"
                class="ai-proposal__fail"
              >
                已被新的下单提议替代，请以最新那条为准
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

    <!-- 下单前勾选商品：把「去购物车勾选」搬进对话，勾完直接生成提议 -->
    <van-popup
      v-model:show="cartVisible"
      position="bottom"
      round
      closeable
      class="ai-cart-popup"
      :style="{ maxHeight: '78vh' }"
    >
      <div class="ai-cart">
        <div class="ai-cart__head">
          <h3 class="ai-cart__title">选择要下单的商品</h3>
          <!-- 地址必须让用户确认：默认地址只是初值，寄到哪由用户说了算 -->
          <button class="ai-cart__addr" type="button" @click="handlePickAddress">
            <van-icon name="location-o" />
            <span class="ai-cart__addr-text">{{
              cartSelect.addressText || '请选择收货地址'
            }}</span>
            <van-icon name="arrow" class="ai-cart__addr-more" />
          </button>
        </div>

        <div class="ai-cart__list">
          <div v-for="item in cartSelect.items" :key="item.cartId" class="ai-cart__row">
            <van-checkbox v-model="item.checked" shape="square" />
            <div class="ai-cart__thumb">
              <img v-if="item.picUrl" :src="item.picUrl" :alt="item.goodsName" />
              <span v-else class="ai-cart__placeholder">零</span>
            </div>
            <div class="ai-cart__info">
              <p class="ai-cart__name">{{ item.goodsName }}</p>
              <p class="ai-cart__price">￥{{ item.unitPrice }}</p>
            </div>
            <!-- 数量直接在这里改：改动立即落购物车，生成提议时按这个数量计算 -->
            <van-stepper
              class="ai-cart__stepper"
              :model-value="item.number"
              :min="1"
              :max="99"
              integer
              button-size="24px"
              :disabled="item.savingNumber"
              @change="(value) => handleChangeNumber(item, value)"
            />
          </div>
        </div>

        <div class="ai-cart__foot">
          <div class="ai-cart__sum">
            <span class="ai-cart__sum-label">已选 {{ selectedCartCount }} 件</span>
            <strong class="ai-cart__sum-amount">￥{{ selectedCartAmount }}</strong>
          </div>
          <van-button
            type="primary"
            round
            class="ai-cart__submit"
            :loading="cartSelect.submitting"
            :disabled="!selectedCartCount"
            @click="handleSubmitCart"
            >生成下单提议</van-button
          >
        </div>
      </div>
    </van-popup>

    <!-- 收货地址选择：叠在勾选弹层之上，选完立即回到商品列表 -->
    <van-popup
      v-model:show="addressPickerVisible"
      position="bottom"
      round
      closeable
      class="ai-addr-popup"
      :style="{ maxHeight: '70vh' }"
    >
      <div class="ai-addr">
        <h3 class="ai-addr__title">选择收货地址</h3>

        <div class="ai-addr__list">
          <div
            v-for="address in cartSelect.addresses"
            :key="address.id"
            class="ai-addr__row"
            :class="{ 'is-active': address.id === cartSelect.addressId }"
            @click="handleSelectAddress(address)"
          >
            <div class="ai-addr__info">
              <p class="ai-addr__who">
                <span class="ai-addr__name">{{ address.name }}</span>
                <span class="ai-addr__tel">{{ maskTel(address.tel) }}</span>
                <van-tag v-if="address.isDefault" type="primary" plain>默认</van-tag>
              </p>
              <p class="ai-addr__where">{{ addressLine(address) }}</p>
            </div>
            <van-icon
              v-if="address.id === cartSelect.addressId"
              name="success"
              class="ai-addr__check"
            />
          </div>
        </div>

        <div v-if="cartSelect.addressesLoading" class="ai-addr__loading">
          <van-loading size="18px">正在加载地址</van-loading>
        </div>
        <van-empty
          v-else-if="!cartSelect.addresses.length"
          description="还没有收货地址，先添加一个才能下单"
          image-size="72"
        />

        <van-button class="ai-addr__add" block round plain type="primary" @click="handleAddAddress">
          新增收货地址
        </van-button>
      </div>
    </van-popup>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRefs, watch } from 'vue'

import {
  addProductToCart,
  aiChatState,
  changeCartItemNumber,
  clearSession,
  closeCartAddressPicker,
  closeCartSelection,
  confirmProposal,
  ensureHistory,
  goAddCartAddress,
  loadCartAddresses,
  openCartAddressPicker,
  reopenCartSelection,
  selectCartAddress,
  sendMessage,
  stopStream,
  submitCartSelection
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

/** 购物车勾选弹层状态。 */
const cartSelect = computed(() => aiChatState.cartSelect)

/** 弹层显隐：用 computed 双向绑定，关闭时统一走 store 的关闭动作。 */
const cartVisible = computed({
  get: () => aiChatState.cartSelect.visible,
  set: (value) => {
    if (!value) {
      closeCartSelection()
    }
  }
})

/** 地址选择层显隐：同样收口到 store，避免两处各存一份状态。 */
const addressPickerVisible = computed({
  get: () => aiChatState.cartSelect.addressPickerVisible,
  set: (value) => {
    if (!value) {
      closeCartAddressPicker()
    }
  }
})

/** 已勾选的购物车行。 */
const selectedCartItems = computed(() => aiChatState.cartSelect.items.filter((item) => item.checked))

/** 已勾选件数（按数量累加，与购物车页口径一致）。 */
const selectedCartCount = computed(() =>
  selectedCartItems.value.reduce((total, item) => total + (item.number || 0), 0)
)

/** 已勾选金额，仅用于弹层内的即时估算；最终金额以服务端提议为准。 */
const selectedCartAmount = computed(() => {
  const amount = selectedCartItems.value.reduce(
    (total, item) => total + Number(item.unitPrice || 0) * (item.number || 0),
    0
  )
  return amount.toFixed(2)
})

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

/** 商品卡加购：写购物车，不创建订单。 */
const handleAddCart = (card) => {
  addProductToCart(card)
}

/** 提交购物车勾选，生成待确认提议。 */
const handleSubmitCart = () => {
  submitCartSelection()
}

/** 弹层内改数量：立即落库，避免提议金额与弹层显示不一致。 */
const handleChangeNumber = (item, value) => {
  changeCartItemNumber(item, value)
}

/** 重新打开上次关掉的勾选弹层。 */
const handleReopenCart = (message) => {
  reopenCartSelection(message)
}

/** 打开收货地址选择层。 */
const handlePickAddress = () => {
  openCartAddressPicker()
}

/** 选中地址：立即回填到弹层，提交时随提议一起带给服务端。 */
const handleSelectAddress = (address) => {
  selectCartAddress(address)
}

/** 去新增地址；返回后勾选弹层仍在，地址列表会自动重新拉取。 */
const handleAddAddress = () => {
  goAddCartAddress()
}

/** 手机号脱敏，与服务端摘要口径一致。 */
const maskTel = (tel) => {
  const text = String(tel || '')
  return text.length === 11 ? `${text.slice(0, 3)}****${text.slice(7)}` : text
}

/** 地址单行展示：省市区 + 门牌。 */
const addressLine = (address) => {
  if (!address) {
    return ''
  }
  const region = [address.province, address.city, address.county].filter(Boolean).join('')
  return `${region} ${address.addressDetail || ''}`.trim()
}

onMounted(() => {
  ensureHistory()
  // 从地址页返回时回到这里：弹层可能还开着，重新拉一次地址才能看到刚新增的那条
  loadCartAddresses()
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

  /* 寄送地址：确认前必须可见，否则用户是在不知道寄到哪的情况下点确认 */
  &__addr {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    margin: 12px 0 0;
    font-size: 22px;
    line-height: 1.45;
    color: var(--wb-text-secondary);

    &.is-warn {
      color: #ff9500;
    }
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
    flex-wrap: wrap;
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
  align-items: center;
  gap: 12px;

  &__label {
    font-size: 21px;
    color: var(--wb-text-tertiary);
  }

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

/* 首字未到时的三点占位：让「已收到、正在处理」立刻可见 */
.ai-msg__bubble--pending {
  padding: 28px 26px;
}

.ai-typing {
  display: inline-flex;
  align-items: center;
  gap: 8px;

  i {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: var(--wb-text-tertiary);
    animation: ai-typing-blink 1.2s infinite ease-in-out;

    &:nth-child(2) {
      animation-delay: 0.2s;
    }

    &:nth-child(3) {
      animation-delay: 0.4s;
    }
  }
}

@keyframes ai-typing-blink {
  0%,
  80%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }

  40% {
    opacity: 1;
    transform: translateY(-2px);
  }
}

/* 商品卡：固定高度的一行，图 / 名称 / 价格 / 销量 / 加购按钮，避免图片撑满整屏 */
.ai-products {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ai-product {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  border-radius: var(--wb-radius-soft);
  background: var(--wb-surface-elevated);
  border: 1px solid var(--wb-border);
  box-shadow: var(--wb-shadow-card);

  &__thumb {
    flex: none;
    width: 96px;
    height: 96px;
    border-radius: 12px;
    overflow: hidden;
    background: var(--wb-surface-subtle);
    display: flex;
    align-items: center;
    justify-content: center;

    img {
      display: block;
      width: 100%;
      height: 100%;
      max-width: 100%;
      max-height: 100%;
      object-fit: cover;
    }
  }

  &__placeholder {
    font-size: 26px;
    color: var(--wb-text-tertiary);
  }

  &__body {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  &__name {
    margin: 0;
    font-size: 24px;
    line-height: 1.3;
    color: var(--wb-text);
    /* 固定单行：名称长短不影响卡片高度，也不会把价格挤到第二屏 */
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__meta {
    display: flex;
    align-items: baseline;
    gap: 10px;
    white-space: nowrap;
  }

  &__price {
    font-size: 28px;
    font-weight: 600;
    color: #ff3b30;
  }

  &__sales {
    font-size: 20px;
    color: var(--wb-text-tertiary);
  }

  &__btn {
    flex: none;
    width: 168px;
    height: 52px;
    padding: 0;
    font-size: 24px;
    border-radius: 26px;
  }
}

.ai-cart-reopen {
  display: flex;
  padding: 8px 0 0;
}

/* 购物车勾选弹层 */
.ai-cart-popup {
  border-radius: 24px 24px 0 0;
}

.ai-cart {
  display: flex;
  flex-direction: column;
  max-height: 78vh;
  padding: 32px 24px calc(24px + env(safe-area-inset-bottom));

  &__head {
    padding-right: 48px;
  }

  &__title {
    margin: 0;
    font-size: 30px;
    font-weight: 600;
    color: var(--wb-text);
  }

  /* 地址行是按钮：默认地址只是初值，点它才能换 */
  &__addr {
    margin: 10px 0 0;
    padding: 10px 12px;
    width: 100%;
    display: flex;
    align-items: center;
    gap: 8px;
    border: 1px solid var(--wb-border);
    border-radius: var(--wb-radius-soft);
    background: var(--wb-surface-subtle);
    font-family: inherit;
    font-size: 22px;
    line-height: 1.4;
    color: var(--wb-text-secondary);
    text-align: left;
    -webkit-tap-highlight-color: transparent;
  }

  &__addr-text {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__addr-more {
    flex: none;
    color: var(--wb-text-tertiary);
  }

  &__list {
    flex: 1;
    margin-top: 20px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 14px;
    border-radius: var(--wb-radius-soft);
    background: var(--wb-surface-subtle);
  }

  &__thumb {
    flex: none;
    width: 72px;
    height: 72px;
    border-radius: 12px;
    overflow: hidden;
    background: var(--wb-surface-elevated);
    display: flex;
    align-items: center;
    justify-content: center;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  &__placeholder {
    font-size: 24px;
    color: var(--wb-text-tertiary);
  }

  &__info {
    flex: 1;
    min-width: 0;
  }

  &__name {
    margin: 0;
    font-size: 24px;
    line-height: 1.4;
    color: var(--wb-text);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &__price {
    margin: 8px 0 0;
    font-size: 22px;
    color: var(--wb-text-secondary);
  }

  /* 数量选择器：与勾选框、缩略图同一行，宽度固定，避免把商品名挤没 */
  &__stepper {
    flex: none;

    :deep(.van-stepper__input) {
      width: 56px;
      font-size: 22px;
    }
  }

  &__foot {
    margin-top: 20px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
  }

  &__sum {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  &__sum-label {
    font-size: 21px;
    color: var(--wb-text-tertiary);
  }

  &__sum-amount {
    font-size: 32px;
    color: #ff3b30;
  }

  &__submit {
    min-width: 240px;
    height: 76px;
    font-size: 26px;
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

/* 收货地址选择层：与勾选弹层同一套视觉，避免像两个不同页面 */
.ai-addr {
  display: flex;
  flex-direction: column;
  max-height: 70vh;
  padding: 32px 24px calc(24px + env(safe-area-inset-bottom));
  padding-right: 48px;

  &__title {
    margin: 0;
    font-size: 30px;
    font-weight: 600;
    color: var(--wb-text);
  }

  &__list {
    flex: 1;
    margin-top: 20px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  &__row {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 18px 20px;
    border-radius: var(--wb-radius-soft);
    background: var(--wb-surface-subtle);
    border: 1px solid transparent;

    &.is-active {
      border-color: rgba(0, 113, 227, 0.45);
      background: rgba(0, 113, 227, 0.06);
    }
  }

  &__info {
    flex: 1;
    min-width: 0;
  }

  &__who {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 12px;
    margin: 0;
    font-size: 24px;
    color: var(--wb-text);
  }

  &__name {
    font-weight: 600;
  }

  &__tel {
    color: var(--wb-text-secondary);
  }

  &__where {
    margin: 8px 0 0;
    font-size: 22px;
    line-height: 1.45;
    color: var(--wb-text-secondary);
  }

  &__check {
    flex: none;
    color: #0071e3;
    font-size: 32px;
  }

  &__loading {
    padding: 28px 0;
    display: flex;
    justify-content: center;
  }

  &__add {
    margin-top: 20px;
  }
}
</style>
