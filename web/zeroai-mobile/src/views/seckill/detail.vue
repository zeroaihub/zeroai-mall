<template>
  <div class="seckill-detail wb-page">
    <nav-bar title="秒杀详情" />

    <main class="seckill-detail__body">
      <section class="detail-hero">
        <div class="detail-hero__copy">
          <span class="detail-hero__chip">FLASH DROP</span>
          <h1>{{ activity.name || '秒杀活动' }}</h1>
          <p>{{ activity.brief || '618 爆款限量开抢' }}</p>
          <div class="detail-hero__countdown">
            <span>{{ detailCountdownLabel }}</span>
            <b>{{ detailCountdown.hours }}</b>
            <i>时</i>
            <b>{{ detailCountdown.minutes }}</b>
            <i>分</i>
            <b>{{ detailCountdown.seconds }}</b>
            <i>秒</i>
          </div>
        </div>
        <div class="detail-hero__product" :class="{ 'detail-hero__product--image': selectedProductPicUrl }" aria-hidden="true">
          <img
            v-if="selectedProductPicUrl"
            class="detail-hero__image"
            :src="selectedProductPicUrl"
            alt=""
          >
          <i class="detail-hero__bag" />
          <i class="detail-hero__earbuds" />
          <i class="detail-hero__watch" />
          <i class="detail-hero__stage" />
        </div>
      </section>

      <section class="panel">
        <div class="panel__head">
          <span>选择商品</span>
          <strong>{{ selectedSku ? '已选择 1 款' : '请选择' }}</strong>
        </div>
        <van-radio-group v-model="selectedSkuId">
          <button
            v-for="sku in skuList"
            :key="sku.id"
            type="button"
            class="sku-card"
            :class="{ 'sku-card--active': selectedSkuId === sku.id }"
            @click="selectedSkuId = sku.id"
          >
            <van-radio :name="sku.id" />
            <span class="sku-card__thumb" :class="{ 'sku-card__thumb--image': sku.productPicUrl }" aria-hidden="true">
              <img
                v-if="sku.productPicUrl"
                class="sku-card__thumb-img"
                :src="sku.productPicUrl"
                alt=""
              >
              <i class="sku-card__thumb-bag" />
              <i class="sku-card__thumb-earbuds" />
              <i class="sku-card__thumb-watch" />
              <i class="sku-card__thumb-stage" />
            </span>
            <div class="sku-card__content">
              <strong>商品 {{ sku.goodsId }}</strong>
              <span>货品 {{ sku.productId }} · 库存 {{ sku.availableStock }} · 限购 {{ sku.limitCount || 1 }}</span>
              <small>秒杀价</small>
              <em>¥{{ Number(sku.seckillPrice).toFixed(2) }}</em>
            </div>
          </button>
        </van-radio-group>
      </section>

      <button type="button" class="address-card" @click="goAddress">
        <span>收货地址</span>
        <strong>{{ selectedAddress.id ? selectedAddress.name : '请选择收货地址' }}</strong>
        <van-icon name="arrow" />
      </button>

      <button type="button" class="notice-card">
        <van-icon name="info-o" />
        <span>秒杀商品不支持使用优惠券、积分及红包</span>
        <van-icon name="arrow" />
      </button>

      <section class="panel panel--compact">
        <div class="quantity-row">
          <span>抢购数量</span>
          <van-stepper v-model="number" integer :min="1" :max="selectedSku?.limitCount || 1" />
        </div>
        <div v-if="selectedAddress.id" class="address-detail">
          {{ fullAddress }}
        </div>
        <van-field
          class="message-field"
          v-model="message"
          rows="2"
          autosize
          type="textarea"
          placeholder="订单备注，可选"
        />
      </section>

      <section v-if="orderSn" class="result-panel">
        <span>处理结果</span>
        <strong>{{ resultStatusText }}</strong>
        <p>{{ resultText }}</p>
        <van-button
          v-if="result.status === 'SUCCESS'"
          block
          round
          type="primary"
          @click="goPay"
        >去支付</van-button>
      </section>

      <div class="submit-bar">
        <div class="submit-bar__main">
          <div>
            <span>预计支付</span>
            <strong>¥{{ estimatedPrice }}</strong>
          </div>
          <van-button
            round
            type="primary"
            :loading="submitLoading"
            loading-text="提交中"
            @click="submitOrder"
          >立即秒杀</van-button>
        </div>
        <p><van-icon name="lock" /> 60 秒锁库存，超时未支付将自动取消订单</p>
      </div>
      <div class="submit-placeholder" />
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useStore } from 'vuex'
import { showLoadingToast, showToast } from 'vant'

import NavBar from '@/components/NavBar'
import {
  seckillDetail,
  seckillResult,
  seckillSubmit,
  seckillToken
} from '@/api/seckill'
import {
  buildSeckillPayRoute,
  buildSeckillSubmitErrorMessage,
} from './seckill-payment-flow.mjs'

const props = defineProps({
  activityId: {
    type: [String, Number],
    required: true
  }
})

const router = useRouter()
const store = useStore()
const activity = ref({})
const skuList = ref([])
const selectedSkuId = ref()
const number = ref(1)
const message = ref('')
const orderSn = ref('')
const submitLoading = ref(false)
const actualPrice = ref(0)
const nowTime = ref(Date.now())
const result = ref({
  status: '',
  failReason: ''
})
let pollTimer = null
let tickTimer = null

const selectedAddress = computed(() => store.getters.selectedAddress || {})
const selectedSku = computed(() => skuList.value.find((item) => item.id === selectedSkuId.value))
const selectedProductPicUrl = computed(() => selectedSku.value?.productPicUrl || '')
const estimatedPrice = computed(() => {
  const price = Number(selectedSku.value?.seckillPrice || 0)
  return (price * number.value).toFixed(2)
})
const parseTime = (value) => {
  if (!value) return 0
  const time = new Date(String(value).replace(/-/g, '/')).getTime()
  return Number.isNaN(time) ? 0 : time
}
const padTime = (value) => String(value).padStart(2, '0')
const detailCountdownLabel = computed(() => {
  const start = parseTime(activity.value.startTime)
  if (start && nowTime.value < start) return '距开始'
  return '距结束'
})
const detailCountdown = computed(() => {
  const start = parseTime(activity.value.startTime)
  const end = parseTime(activity.value.endTime)
  const target = start && nowTime.value < start ? start : end
  const diff = Math.max(0, (target || nowTime.value) - nowTime.value)
  const hours = Math.floor(diff / 1000 / 60 / 60)
  return {
    hours: hours > 99 ? '99+' : padTime(hours),
    minutes: padTime(Math.floor(diff / 1000 / 60) % 60),
    seconds: padTime(Math.floor(diff / 1000) % 60)
  }
})
const fullAddress = computed(() => {
  const address = selectedAddress.value
  if (!address?.id) {
    return '提交秒杀订单前需要选择收货地址'
  }
  return address.address || `${address.province || ''}${address.city || ''}${address.county || ''} ${address.addressDetail || ''}`.trim()
})
const resultStatusText = computed(() => {
  if (result.value.status === 'SUCCESS') return '订单已创建'
  if (result.value.status === 'FAILED') return '创建失败'
  if (result.value.status === 'CLOSED') return '超时关闭'
  return '处理中'
})
const resultText = computed(() => {
  if (result.value.status === 'SUCCESS') return '订单创建成功，请在 60 秒内完成支付。'
  if (result.value.status === 'FAILED') return result.value.failReason || '秒杀订单创建失败，请稍后重试。'
  if (result.value.status === 'CLOSED') return '订单超时未支付，活动库存已释放。'
  return '订单正在异步创建，请稍等。'
})

const loadDetail = async () => {
  const res = await seckillDetail(props.activityId)
  activity.value = res?.data?.activity || {}
  skuList.value = res?.data?.skuList || []
  selectedSkuId.value = skuList.value[0]?.id
}

const loadDefaultAddress = async () => {
  if (selectedAddress.value?.id) {
    return
  }
  try {
    const list = await store.dispatch('address/getList')
    const defaultItem = (list || []).find((item) => item.isDefault) || (list || [])[0]
    if (defaultItem) {
      store.commit('address/SET_SELECTED_ADDRESS', defaultItem)
    }
  } catch (error) {
    console.log(error)
  }
}

const goAddress = () => {
  router.push('/address')
}

const submitOrder = async () => {
  if (!selectedSku.value) {
    showToast({ type: 'fail', message: '请选择秒杀商品' })
    return
  }
  if (!selectedAddress.value?.id) {
    showToast({ type: 'fail', message: '请选择收货地址' })
    return
  }
  submitLoading.value = true
  const loadingToast = showLoadingToast({
    message: '正在提交秒杀订单',
    forbidClick: true,
    duration: 0
  })
  let submitError = null
  try {
    const tokenRes = await seckillToken(selectedSku.value.id)
    const submitRes = await seckillSubmit({
      activitySkuId: selectedSku.value.id,
      number: number.value,
      addressId: selectedAddress.value.id,
      seckillToken: tokenRes?.data?.token,
      message: message.value
    })
    orderSn.value = submitRes?.data?.orderSn
    actualPrice.value = Number(submitRes?.data?.actualPrice || estimatedPrice.value)
    result.value = {
      status: submitRes?.data?.status || 'PROCESSING'
    }
    if (redirectToPayIfSuccess(result.value)) {
      return
    }
    startPolling()
  } catch (error) {
    submitError = error
  } finally {
    loadingToast.close()
    submitLoading.value = false
    if (submitError) {
      showToast({ type: 'fail', message: buildSeckillSubmitErrorMessage(submitError) })
    }
  }
}

const startPolling = () => {
  stopPolling()
  pollTimer = window.setInterval(async () => {
    if (!orderSn.value) return
    const res = await seckillResult(orderSn.value)
    result.value = res?.data || {}
    if (['SUCCESS', 'FAILED', 'CLOSED'].includes(result.value.status)) {
      stopPolling()
      redirectToPayIfSuccess(result.value)
    }
  }, 1200)
}

const stopPolling = () => {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

const goPay = () => {
  redirectToPayIfSuccess({ status: 'SUCCESS', actualPrice: result.value.actualPrice })
}

const redirectToPayIfSuccess = (payResult) => {
  const route = buildSeckillPayRoute({
    orderSn: orderSn.value,
    actualPrice: payResult?.actualPrice || actualPrice.value,
    fallbackPrice: estimatedPrice.value,
    status: payResult?.status
  })

  if (!route) {
    return false
  }

  router.push(route)
  return true
}

onMounted(() => {
  loadDetail()
  loadDefaultAddress()
  tickTimer = window.setInterval(() => {
    nowTime.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  stopPolling()
  if (tickTimer) {
    window.clearInterval(tickTimer)
  }
})
</script>

<style lang="scss" scoped>
.seckill-detail {
  --seckill-primary: #0066cc;
  --seckill-primary-focus: #0071e3;
  --seckill-canvas: #ffffff;
  --seckill-canvas-parchment: #f5f5f7;
  --seckill-tile-dark: #272729;
  --seckill-ink: #1d1d1f;
  --seckill-muted: #7a7a7a;
  --seckill-hairline: #e0e0e0;
  --van-primary-color: var(--seckill-primary);

  min-height: 100vh;
  color: var(--seckill-ink);
  background: var(--seckill-canvas-parchment);
  font-family: "SF Pro Display", "SF Pro Text", system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
}

.seckill-detail__body {
  width: 100%;
  max-width: var(--wb-content-width);
  margin: 0 auto;
  padding: 0 18px 40px;
}

.detail-hero {
  position: relative;
  margin: 0 -18px 18px;
  min-height: 450px;
  padding: 46px 28px 206px;
  overflow: hidden;
  color: var(--seckill-ink);
  text-align: center;
  background:
    radial-gradient(circle at 82% 30%, rgba(255, 255, 255, 0.96) 0 22%, transparent 42%),
    linear-gradient(135deg, #fff8f2 0%, #ffffff 48%, #edf5ff 100%);
}

.detail-hero__chip {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  padding: 0 18px;
  border-radius: 999px;
  color: var(--seckill-primary);
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 0.08em;
}

.detail-hero h1 {
  margin: 16px 0 12px;
  font-size: 56px;
  font-weight: 600;
  line-height: 1.07;
  letter-spacing: -0.01em;
}

.detail-hero p {
  max-width: 560px;
  margin: 0 auto;
  color: var(--seckill-muted);
  font-size: 28px;
  line-height: 1.47;
  letter-spacing: -0.01em;
}

.detail-hero__countdown {
  position: relative;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 24px;
}

.detail-hero__countdown span {
  color: var(--seckill-muted);
  font-size: 28px;
  line-height: 1;
}

.detail-hero__countdown b {
  display: grid;
  min-width: 54px;
  height: 52px;
  place-items: center;
  color: var(--seckill-ink);
  font-size: 34px;
  font-weight: 700;
  line-height: 1;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 10px;
}

.detail-hero__countdown i {
  color: var(--seckill-muted);
  font-size: 22px;
  font-style: normal;
  line-height: 1;
}

.detail-hero__product {
  position: absolute;
  left: 50%;
  bottom: 0;
  width: 400px;
  height: 198px;
  transform: translateX(-50%);
}

.detail-hero__product i {
  position: absolute;
  display: block;
}

.detail-hero__image {
  position: absolute;
  right: 18px;
  bottom: 34px;
  z-index: 2;
  width: 78%;
  height: 78%;
  object-fit: contain;
  filter: drop-shadow(3px 5px 30px rgba(0, 0, 0, 0.16));
}

.detail-hero__product--image .detail-hero__bag,
.detail-hero__product--image .detail-hero__earbuds,
.detail-hero__product--image .detail-hero__watch {
  display: none;
}

.detail-hero__stage {
  left: 56px;
  right: 20px;
  bottom: 0;
  height: 94px;
  border-radius: 50% 50% 0 0;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(224, 234, 245, 0.78));
}

.detail-hero__bag {
  right: 42px;
  bottom: 48px;
  width: 104px;
  height: 146px;
  border: 1px solid rgba(29, 29, 31, 0.08);
  border-radius: 22px;
  background:
    linear-gradient(90deg, rgba(29, 29, 31, 0.05) 1px, transparent 1px) 0 0 / 15px 100%,
    linear-gradient(180deg, #fff8ef 0%, #ead5bf 100%);
}

.detail-hero__bag::before {
  position: absolute;
  top: -20px;
  left: 31px;
  width: 50px;
  height: 36px;
  content: '';
  border: 8px solid rgba(29, 29, 31, 0.62);
  border-bottom: 0;
  border-radius: 28px 28px 0 0;
}

.detail-hero__earbuds {
  right: 132px;
  bottom: 52px;
  width: 94px;
  height: 76px;
  border-radius: 32px 32px 24px 24px;
  background: #ffffff;
  border: 1px solid rgba(29, 29, 31, 0.08);
}

.detail-hero__earbuds::before,
.detail-hero__earbuds::after {
  position: absolute;
  top: 15px;
  width: 26px;
  height: 36px;
  content: '';
  background: #edf3fb;
  border-radius: 999px;
}

.detail-hero__earbuds::before {
  left: 21px;
}

.detail-hero__earbuds::after {
  right: 21px;
}

.detail-hero__watch {
  right: 222px;
  bottom: 54px;
  width: 72px;
  height: 104px;
  border: 8px solid #decbbc;
  border-radius: 24px;
  background: #1d1d1f;
}

.detail-hero__watch::before {
  position: absolute;
  inset: 16px 12px;
  content: '';
  border-radius: 50%;
  background: radial-gradient(circle, #ff5a3c 0 18%, #f5f5f7 19% 22%, transparent 23%);
}

.panel,
.result-panel {
  margin-bottom: 18px;
  padding: 24px;
  border: 1px solid var(--seckill-hairline);
  border-radius: 24px;
  background: var(--seckill-canvas);
  box-shadow: none;
}

.panel__head,
.quantity-row,
.address-card,
.submit-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.panel__head {
  margin-bottom: 18px;
  font-size: 28px;
  color: var(--seckill-muted);
}

.panel__head strong {
  color: var(--seckill-primary);
  font-size: 28px;
  font-weight: 600;
}

.sku-card {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 14px;
  padding: 20px;
  border: 1px solid var(--seckill-hairline);
  border-radius: 22px;
  background: var(--seckill-canvas);
  text-align: left;
  transition: transform 0.16s ease, border-color 0.16s ease;
}

.sku-card--active {
  border-color: var(--seckill-primary-focus);
  border-width: 2px;
  background: rgba(0, 102, 204, 0.04);
}

.sku-card:active {
  transform: scale(0.95);
}

.sku-card__content {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sku-card__content strong {
  color: var(--seckill-ink);
  font-size: 32px;
  font-weight: 600;
  line-height: 1.16;
}

.sku-card__content span {
  color: var(--seckill-muted);
  font-size: 28px;
  line-height: 1.35;
}

.sku-card em {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  color: var(--seckill-primary);
  font-size: 36px;
  font-style: normal;
  font-weight: 600;
}

.sku-card em small {
  color: var(--seckill-muted);
  font-size: 24px;
  font-weight: 400;
  line-height: 1;
}

.address-card {
  width: 100%;
  margin-bottom: 18px;
  padding: 20px;
  border: 1px solid var(--seckill-hairline);
  border-radius: 22px;
  background: var(--seckill-canvas-parchment);
  text-align: left;
  transition: transform 0.16s ease;
}

.address-card:active {
  transform: scale(0.95);
}

.address-card strong {
  color: var(--seckill-ink);
  font-size: 32px;
  font-weight: 600;
}

.address-card p {
  margin: 8px 0 0;
  color: var(--seckill-muted);
  font-size: 28px;
  line-height: 1.45;
}

.quantity-row {
  margin-bottom: 16px;
  color: var(--seckill-ink);
  font-size: 30px;
}

.message-field {
  border: 1px solid var(--seckill-hairline);
  border-radius: 22px;
  background: var(--seckill-canvas-parchment);
  overflow: hidden;
}

.message-field :deep(.van-field__control) {
  font-size: 28px;
  line-height: 1.47;
}

.result-panel span {
  color: var(--seckill-muted);
  font-size: 28px;
}

.result-panel strong {
  display: block;
  margin-top: 8px;
  color: var(--seckill-ink);
  font-size: 40px;
  font-weight: 600;
}

.result-panel p {
  color: var(--seckill-muted);
  font-size: 28px;
  line-height: 1.45;
}

.submit-bar {
  position: fixed;
  left: 18px;
  right: 18px;
  bottom: 18px;
  z-index: 5;
  max-width: calc(var(--wb-content-width) - 36px);
  margin: 0 auto;
  padding: 18px 18px calc(18px + env(safe-area-inset-bottom, 0px));
  border: 1px solid rgba(224, 224, 224, 0.72);
  border-radius: 28px;
  background: rgba(245, 245, 247, 0.86);
  backdrop-filter: saturate(180%) blur(20px);
  box-shadow: none;
}

.submit-bar span {
  display: block;
  color: var(--seckill-muted);
  font-size: 28px;
}

.submit-bar strong {
  color: var(--seckill-ink);
  font-size: 42px;
  font-weight: 600;
}

.submit-bar :deep(.van-button:active) {
  transform: scale(0.95);
}

.submit-bar :deep(.van-button) {
  min-width: 132px;
  height: 44px;
  border-radius: 999px;
  font-size: 28px;
}

.submit-placeholder {
  height: 130px;
}

@media (max-width: 419px) {
  .detail-hero {
    min-height: 432px;
    padding: 40px 22px 198px;
  }

  .detail-hero h1 {
    font-size: 48px;
  }

  .detail-hero p {
    font-size: 28px;
  }

  .detail-hero__product {
    width: 370px;
    transform: translateX(-50%) scale(.84);
    transform-origin: center bottom;
  }

  .sku-card {
    align-items: center;
  }

  .sku-card em {
    align-items: flex-end;
  }
}

/* Final layout follows the approved seckill detail reference: text-left hero,
   product stage, image-like SKU rows, separate address card and frosted checkout. */
.seckill-detail {
  background:
    radial-gradient(circle at 80% 0, rgba(255, 255, 255, 0.86), transparent 34%),
    var(--seckill-canvas-parchment);
}

.seckill-detail__body {
  padding: 0 18px 36px;
}

.detail-hero {
  display: flex;
  min-height: 600px;
  align-items: center;
  margin: 0 -18px;
  padding: 42px 28px 50px;
  text-align: left;
  background:
    radial-gradient(circle at 78% 46%, rgba(255, 255, 255, 0.96) 0 25%, transparent 50%),
    linear-gradient(135deg, #ffffff 0%, #ffffff 38%, #eef6ff 100%);
}

.detail-hero::before {
  position: absolute;
  top: 40px;
  right: -110px;
  width: 520px;
  height: 520px;
  content: '';
  border-radius: 50%;
  background: rgba(0, 102, 204, 0.045);
}

.detail-hero__copy {
  position: relative;
  z-index: 2;
  width: 54%;
}

.detail-hero__chip {
  min-height: auto;
  padding: 0;
  font-size: 30px;
}

.detail-hero h1 {
  margin: 28px 0 16px;
  font-size: 58px;
}

.detail-hero p {
  margin: 0;
  font-size: 30px;
}

.detail-hero__countdown {
  gap: 12px;
  margin-top: 62px;
}

.detail-hero__countdown span,
.detail-hero__countdown i {
  font-size: 28px;
}

.detail-hero__countdown b {
  min-width: 64px;
  height: 58px;
  font-size: 34px;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.04);
}

.detail-hero__product {
  left: auto;
  right: -28px;
  bottom: 70px;
  z-index: 1;
  width: 360px;
  height: 380px;
  transform: none;
}

.detail-hero__stage {
  left: 18px;
  right: -26px;
  bottom: 0;
  height: 126px;
  border-radius: 50%;
  background: linear-gradient(180deg, #ffffff, #dfeaf6);
}

.detail-hero__bag {
  right: 72px;
  bottom: 100px;
  width: 146px;
  height: 232px;
  border-radius: 26px;
  background:
    linear-gradient(90deg, rgba(29, 29, 31, 0.08) 1px, transparent 1px) 0 0 / 18px 100%,
    linear-gradient(180deg, #fff4e8 0%, #ead1b9 100%);
}

.detail-hero__bag::before {
  top: -24px;
  left: 40px;
  width: 66px;
  height: 46px;
  border-width: 9px;
}

.detail-hero__earbuds {
  right: 28px;
  bottom: 74px;
  width: 130px;
  height: 104px;
  border-radius: 38px 38px 30px 30px;
}

.detail-hero__earbuds::before,
.detail-hero__earbuds::after {
  top: 20px;
  width: 32px;
  height: 42px;
}

.detail-hero__earbuds::before {
  left: 28px;
}

.detail-hero__earbuds::after {
  right: 28px;
}

.detail-hero__watch {
  right: 164px;
  bottom: 80px;
  width: 104px;
  height: 150px;
  border-color: #d9c5b7;
  border-radius: 28px;
}

.detail-hero__watch::before {
  inset: 42px 33px;
  background: radial-gradient(circle, #ffe46a 0 18%, #ffffff 19% 22%, transparent 23%);
}

.panel,
.result-panel {
  position: relative;
  z-index: 2;
  margin-top: -2px;
  margin-bottom: 18px;
  padding: 28px 24px;
  border-color: rgba(0, 0, 0, 0.04);
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.94);
}

.panel__head {
  margin-bottom: 22px;
  color: var(--seckill-ink);
  font-size: 30px;
}

.panel__head strong {
  font-size: 30px;
}

.sku-card {
  gap: 22px;
  min-height: 176px;
  margin-bottom: 22px;
  padding: 22px;
  border-color: rgba(0, 0, 0, 0.08);
  border-radius: 18px;
  background: #ffffff;
}

.sku-card--active {
  border-color: var(--seckill-primary-focus);
  background: linear-gradient(135deg, #ffffff 0%, #f4f9ff 100%);
}

.sku-card :deep(.van-radio__icon) {
  font-size: 42px;
}

.sku-card :deep(.van-radio__icon--checked .van-icon) {
  color: #ffffff;
  background: var(--seckill-primary);
  border-color: var(--seckill-primary);
}

.sku-card__thumb {
  position: relative;
  flex: 0 0 118px;
  width: 118px;
  height: 118px;
  overflow: hidden;
  border: 1px solid rgba(0, 0, 0, 0.04);
  border-radius: 12px;
  background: linear-gradient(135deg, #ffffff, #f4f7fb);
}

.sku-card__thumb i {
  position: absolute;
  display: block;
}

.sku-card__thumb-img {
  position: absolute;
  inset: 10px;
  z-index: 2;
  width: calc(100% - 20px);
  height: calc(100% - 20px);
  object-fit: contain;
}

.sku-card__thumb--image .sku-card__thumb-bag,
.sku-card__thumb--image .sku-card__thumb-earbuds,
.sku-card__thumb--image .sku-card__thumb-watch {
  display: none;
}

.sku-card__thumb-stage {
  left: 15px;
  right: 9px;
  bottom: 14px;
  height: 30px;
  border-radius: 50%;
  background: #e5eef8;
}

.sku-card__thumb-bag {
  right: 25px;
  bottom: 34px;
  width: 42px;
  height: 70px;
  border-radius: 8px;
  background:
    linear-gradient(90deg, rgba(29, 29, 31, 0.06) 1px, transparent 1px) 0 0 / 8px 100%,
    linear-gradient(180deg, #fff4e8, #ead1b9);
}

.sku-card__thumb-bag::before {
  position: absolute;
  top: -8px;
  left: 12px;
  width: 18px;
  height: 13px;
  content: '';
  border: 4px solid rgba(29, 29, 31, 0.6);
  border-bottom: 0;
  border-radius: 12px 12px 0 0;
}

.sku-card__thumb-earbuds {
  right: 4px;
  bottom: 26px;
  width: 44px;
  height: 34px;
  border: 1px solid rgba(29, 29, 31, 0.05);
  border-radius: 14px 14px 10px 10px;
  background: #ffffff;
}

.sku-card__thumb-earbuds::before,
.sku-card__thumb-earbuds::after {
  position: absolute;
  top: 8px;
  width: 10px;
  height: 14px;
  content: '';
  background: #edf3fb;
  border-radius: 999px;
}

.sku-card__thumb-earbuds::before {
  left: 10px;
}

.sku-card__thumb-earbuds::after {
  right: 10px;
}

.sku-card__thumb-watch {
  left: 22px;
  bottom: 28px;
  width: 36px;
  height: 52px;
  border: 4px solid #d9c5b7;
  border-radius: 10px;
  background: #1d1d1f;
}

.sku-card__thumb-watch::before {
  position: absolute;
  inset: 17px 12px;
  content: '';
  border-radius: 50%;
  background: #ffe46a;
}

.sku-card__content {
  gap: 10px;
  align-items: flex-start;
}

.sku-card__content strong {
  font-size: 34px;
}

.sku-card__content span {
  font-size: 28px;
  line-height: 1.35;
}

.sku-card__content small {
  align-self: flex-start;
  padding: 5px 14px;
  color: var(--seckill-primary);
  background: rgba(0, 102, 204, 0.08);
  border-radius: 999px;
  font-size: 28px;
  line-height: 1;
}

.sku-card__content em {
  display: block;
  align-self: flex-start;
  color: var(--seckill-primary);
  font-size: 38px;
  font-style: normal;
  font-weight: 700;
  line-height: 1;
}

.address-card,
.notice-card {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 18px;
  padding: 30px 28px;
  color: var(--seckill-ink);
  border: 1px solid rgba(0, 0, 0, 0.04);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.94);
  text-align: left;
}

.address-card span {
  flex: 1;
  font-size: 30px;
}

.address-card strong {
  max-width: 55%;
  color: var(--seckill-muted);
  font-size: 28px;
  font-weight: 400;
  text-align: right;
}

.notice-card {
  min-height: 64px;
  padding: 18px 24px;
  color: var(--seckill-muted);
  background: rgba(255, 255, 255, 0.7);
}

.notice-card span {
  flex: 1;
  font-size: 28px;
  line-height: 1.35;
}

.panel--compact {
  margin-top: 0;
  padding: 24px;
}

.quantity-row {
  margin-bottom: 16px;
  font-size: 30px;
}

.address-detail {
  margin-bottom: 16px;
  color: var(--seckill-muted);
  font-size: 28px;
  line-height: 1.4;
}

.message-field {
  background: #f8f9fb;
}

.submit-bar {
  display: block;
  left: 0;
  right: 0;
  bottom: 0;
  max-width: none;
  padding: 24px 24px calc(22px + env(safe-area-inset-bottom, 0px));
  border: 0;
  border-radius: 28px 28px 0 0;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: saturate(180%) blur(20px);
}

.submit-bar__main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 22px;
}

.submit-bar span {
  color: var(--seckill-muted);
  font-size: 28px;
}

.submit-bar strong {
  display: block;
  margin-top: 4px;
  font-size: 46px;
}

.submit-bar :deep(.van-button) {
  min-width: 240px;
  height: 72px;
  font-size: 30px;
  font-weight: 600;
  background: linear-gradient(135deg, #0071e3, #0066cc);
  border: 0;
}

.submit-bar p {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 18px 0 0;
  color: var(--seckill-muted);
  font-size: 28px;
  line-height: 1.35;
}

.submit-placeholder {
  height: 188px;
}

@media (max-width: 419px) {
  .detail-hero {
    min-height: 454px;
    padding: 34px 22px 36px;
  }

  .detail-hero__copy {
    width: 60%;
  }

  .detail-hero h1 {
    font-size: 48px;
  }

  .detail-hero p {
    font-size: 28px;
  }

  .detail-hero__countdown {
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 34px;
  }

  .detail-hero__countdown b {
    min-width: 52px;
    height: 50px;
    font-size: 30px;
  }

  .detail-hero__product {
    right: -42px;
    bottom: 38px;
    width: 330px;
    height: 330px;
    transform: scale(.82);
    transform-origin: right bottom;
  }

  .panel {
    padding: 24px 18px;
  }

  .sku-card {
    gap: 14px;
    padding: 18px 14px;
  }

  .sku-card__thumb {
    flex-basis: 96px;
    width: 96px;
    height: 96px;
  }

  .sku-card__content strong {
    font-size: 32px;
  }

  .sku-card__content span,
  .sku-card__content small,
  .sku-card__content em {
    font-size: 28px;
  }

  .submit-bar :deep(.van-button) {
    min-width: 190px;
  }
}
</style>
