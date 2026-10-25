<template>
  <div class="home wb-page wb-page--tabbed">
    <Header />

    <main class="home__body">
      <section class="home__section home__section--hero">
        <Swiper :banner-list="bannerList" />
      </section>

      <section class="home__section">
        <CouponEntry :coupon-list="couponList" />
      </section>

      <section class="home__section">
        <button class="home__seckill-entry" type="button" @click="goSeckill">
          <span class="home__seckill-bg" aria-hidden="true">
            <img class="home__seckill-bg-image" :src="seckillEntryBg" alt="">
          </span>
          <span class="home__seckill-status">进行中</span>
          <span class="home__seckill-content">
            <span class="home__seckill-kicker">FLASH DROP</span>
            <strong>限时秒杀</strong>
            <small>618 爆款限量开抢</small>
            <span class="home__seckill-countdown">
              <span class="home__seckill-countdown-label">距结束</span>
              <span class="home__seckill-countdown-box">
                <b>{{ flashCountdown.hours }}</b>
                <i>时</i>
              </span>
              <span class="home__seckill-countdown-box">
                <b>{{ flashCountdown.minutes }}</b>
                <i>分</i>
              </span>
              <span class="home__seckill-countdown-box">
                <b>{{ flashCountdown.seconds }}</b>
                <i>秒</i>
              </span>
            </span>
            <span class="home__seckill-action">
              立即开抢
              <van-icon name="arrow" />
            </span>
          </span>
        </button>
      </section>

      <section class="home__section">
        <Diamond :diamond-list="diamondList" />
      </section>

      <section class="home__section">
        <NewArrivals :goods-list="newGoodsList" />
      </section>

      <section class="home__section">
        <HotShelf :goods-list="hotGoodsList" />
      </section>

      <section class="home__section home__section--last">
        <RecommendFeed
          v-model="isLoading"
          :goods-list="goodsList"
          :is-finished="isFinished"
          @onReachBottom="onReachBottom"
        />
      </section>
    </main>

    <back-top />
    <Skeleton v-if="isSkeletonShow" />
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, toRefs } from 'vue'
import { useRouter } from 'vue-router'

import { getHomeData, getRecommonGoodsList } from '@/api/home'
import { orderCoupon } from '@/api/coupon'
import seckillEntryBg from '@/assets/seckill.png'
import Header from './modules/Header'
import Swiper from './modules/Swiper'
import CouponEntry from './modules/CouponEntry'
import Diamond from './modules/Diamond'
import HotShelf from './modules/HotShelf'
import NewArrivals from './modules/NewArrivals'
import RecommendFeed from './modules/RecommendFeed'
import Skeleton from './modules/Skeleton'

const router = useRouter()

const state = reactive({
  bannerList: [],
  diamondList: [],
  newGoodsList: [],
  hotGoodsList: [],
  couponList: [],
  goodsList: [],
  flashCountdown: {
    hours: '00',
    minutes: '00',
    seconds: '00',
  },
  pageSize: 6,
  pageNum: 1,
  isLoading: false,
  isFinished: false,
  isSkeletonShow: true,
})

const {
  bannerList,
  diamondList,
  newGoodsList,
  hotGoodsList,
  couponList,
  goodsList,
  flashCountdown,
  pageSize,
  pageNum,
  isLoading,
  isFinished,
  isSkeletonShow,
} = toRefs(state)

const getHomeIndexData = () => {
  getHomeData().then((res) => {
    const { data } = res
    bannerList.value = data.bannerList || []
    diamondList.value = data.diamondList || []
    newGoodsList.value = data.newGoodsList || []
    hotGoodsList.value = data.hotGoodsList || []
    isSkeletonShow.value = false
  })
}

const getRecommendGoods = () => {
  getRecommonGoodsList({
    pageSize: pageSize.value,
    pageNum: pageNum.value,
  }).then((res) => {
    const { data } = res
    goodsList.value = [...goodsList.value, ...(data || [])]
    isLoading.value = false
    if ((data || []).length < pageSize.value && goodsList.value.length > 0) {
      isFinished.value = true
    }
  })
}

const getCouponList = async () => {
  try {
    const res = await orderCoupon({
      pageSize: 3,
      pageNum: 1,
    })
    couponList.value = res?.data?.records || []
  } catch (error) {
    couponList.value = []
  }
}

const onReachBottom = () => {
  pageNum.value += 1
  getRecommendGoods()
}

const goSeckill = () => {
  router.push('/seckill')
}

let flashTimer = null

const padTime = (value) => String(value).padStart(2, '0')

const updateFlashCountdown = () => {
  const now = new Date()
  const end = new Date(now)
  end.setHours(23, 59, 59, 999)
  const diff = Math.max(0, end.getTime() - now.getTime())
  flashCountdown.value = {
    hours: padTime(Math.floor(diff / 1000 / 60 / 60)),
    minutes: padTime(Math.floor(diff / 1000 / 60) % 60),
    seconds: padTime(Math.floor(diff / 1000) % 60),
  }
}

onMounted(() => {
  getHomeIndexData()
  getRecommendGoods()
  getCouponList()
  updateFlashCountdown()
  flashTimer = window.setInterval(updateFlashCountdown, 1000)
})

onBeforeUnmount(() => {
  if (flashTimer) {
    window.clearInterval(flashTimer)
  }
})
</script>

<style lang="scss" scoped>
.home {
  min-height: 100%;
  
  background: linear-gradient(180deg, #f5f6fa 0%, #f3f4f8 100%);
}

.home__body {
  width: 100%;
  max-width: var(--wb-content-width);
  margin: 0 auto;
  padding: 0 16px 0;
}

.home__section {
  padding-top: 24px;
}

.home__section--hero {
  padding-top: 10px;
}

.home__section--last {
  padding-bottom: 32px;
}

.home__seckill-entry {
  --home-seckill-primary: #0066cc;
  --home-seckill-focus: #0071e3;
  --home-seckill-canvas: #f7fbff;
  --home-seckill-ink: #1d1d1f;
  --home-seckill-muted: #7a7a7a;
  --home-seckill-hairline: rgba(0, 102, 204, 0.12);

  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 360px;
  padding: 34px 26px;
  overflow: hidden;
  color: var(--home-seckill-ink);
  text-align: left;
  border: 1px solid var(--home-seckill-hairline);
  border-radius: 28px;
  background: var(--home-seckill-canvas);
  box-shadow: none;
  transition: transform .16s ease;
}

.home__seckill-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  display: block;
  pointer-events: none;
}

.home__seckill-bg-image {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.home__seckill-entry:active {
  transform: scale(.95);
}

.home__seckill-status {
  position: absolute;
  top: 20px;
  right: 22px;
  z-index: 2;
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  font-size: 28px;
  font-weight: 600;
  line-height: 1;
  color: var(--home-seckill-primary);
  background: rgba(255, 255, 255, 0.78);
  border: 1px solid rgba(0, 102, 204, 0.18);
  border-radius: 999px;
}

.home__seckill-status::before {
  width: 10px;
  height: 10px;
  content: '';
  background: var(--home-seckill-focus);
  border-radius: 50%;
}

.home__seckill-content {
  position: relative;
  z-index: 2;
  display: flex;
  width: 58%;
  flex-direction: column;
  align-items: flex-start;
}

.home__seckill-kicker {
  margin-bottom: 16px;
  color: var(--home-seckill-primary);
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: .08em;
}

.home__seckill-content strong {
  font-size: 58px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: -.01em;
}

.home__seckill-content small {
  margin-top: 12px;
  font-size: 28px;
  font-weight: 400;
  line-height: 1.35;
  color: var(--home-seckill-muted);
}

.home__seckill-countdown {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 28px;
}

.home__seckill-countdown-label {
  color: var(--home-seckill-muted);
  font-size: 28px;
  line-height: 1;
}

.home__seckill-countdown-box {
  display: grid;
  min-width: 56px;
  justify-items: center;
  gap: 6px;
}

.home__seckill-countdown-box b {
  display: grid;
  min-width: 56px;
  height: 52px;
  place-items: center;
  color: var(--home-seckill-ink);
  font-size: 34px;
  font-weight: 700;
  line-height: 1;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(0, 102, 204, 0.12);
  border-radius: 10px;
}

.home__seckill-countdown-box i {
  color: var(--home-seckill-muted);
  font-size: 22px;
  font-style: normal;
  line-height: 1;
}

.home__seckill-action {
  position: relative;
  display: inline-flex;
  min-height: 60px;
  align-items: center;
  gap: 10px;
  padding: 0 26px;
  margin-top: 28px;
  font-size: 28px;
  font-weight: 600;
  line-height: 1;
  color: #fff;
  background: var(--home-seckill-primary);
  border-radius: 999px;
}

.home__seckill-action :deep(.van-icon) {
  width: 30px;
  height: 30px;
  display: inline-grid;
  place-items: center;
  color: var(--home-seckill-primary);
  background: #ffffff;
  border-radius: 50%;
  font-size: 20px;
}

@media (max-width: 375px) {
  .home__body {
    padding-left: 14px;
    padding-right: 14px;
  }

  .home__section {
    padding-top: 22px;
  }

  .home__seckill-entry {
    min-height: 354px;
    padding: 30px 20px;
  }

  .home__seckill-status {
    top: 18px;
    right: 18px;
  }

  .home__seckill-content {
    width: 62%;
  }

  .home__seckill-content strong {
    font-size: 50px;
  }

  .home__seckill-countdown {
    gap: 8px;
    margin-top: 24px;
  }

  .home__seckill-countdown-box {
    min-width: 50px;
  }

  .home__seckill-countdown-box b {
    min-width: 50px;
    height: 48px;
    font-size: 30px;
  }

  .home__seckill-bg-image {
    object-position: center;
  }
}
</style>
