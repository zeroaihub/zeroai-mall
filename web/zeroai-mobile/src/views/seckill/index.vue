<template>
  <div class="seckill wb-page">
    <nav-bar title="限时秒杀" />

    <main class="seckill__body">
      <van-pull-refresh v-model="refreshing" @refresh="refresh">
        <van-list
          v-model:loading="loading"
          :finished="finished"
          finished-text="没有更多活动"
          @load="loadList"
        >
          <article
            v-for="activity in activityList"
            :key="activity.id"
            class="seckill-card"
            :class="getActivityTheme(activity)"
            @click="goDetail(activity)"
          >
            <span class="seckill-card__status" :class="{ 'seckill-card__status--muted': getActivityStatus(activity) !== '进行中' }">
              {{ getActivityStatus(activity) }}
            </span>
            <div class="seckill-card__copy">
              <p class="seckill-card__kicker">{{ getActivityKicker(activity) }}</p>
              <h2>{{ getActivityTitle(activity) }}</h2>
              <p>{{ activity.brief || getActivityBrief(activity) }}</p>
              <div class="seckill-card__countdown">
                <span>{{ getActivityCountdownLabel(activity) }}</span>
                <b>{{ getActivityCountdown(activity).hours }}</b>
                <i>时</i>
                <b>{{ getActivityCountdown(activity).minutes }}</b>
                <i>分</i>
                <b>{{ getActivityCountdown(activity).seconds }}</b>
                <i>秒</i>
              </div>
              <span class="seckill-card__action">
                {{ getActivityAction(activity) }}
                <van-icon name="arrow" />
              </span>
            </div>
            <div class="seckill-card__visual" aria-hidden="true">
              <i class="seckill-card__phone" />
              <i class="seckill-card__earbuds" />
              <i class="seckill-card__speaker" />
              <i class="seckill-card__stage" />
            </div>
          </article>
        </van-list>
      </van-pull-refresh>

      <van-empty v-if="!loading && activityList.length === 0" description="暂无秒杀活动" />
    </main>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import NavBar from '@/components/NavBar'
import { seckillList } from '@/api/seckill'

const router = useRouter()
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const finished = ref(false)
const refreshing = ref(false)
const activityList = ref([])
const nowTime = ref(Date.now())
let tickTimer = null

const loadList = async () => {
  loading.value = true
  const res = await seckillList({
    pageNum: pageNum.value,
    pageSize: pageSize.value
  })
  const records = res?.data?.records || []
  if (refreshing.value) {
    activityList.value = []
    refreshing.value = false
  }
  activityList.value = [...activityList.value, ...records]
  loading.value = false
  if (records.length < pageSize.value) {
    finished.value = true
  } else {
    pageNum.value += 1
  }
}

const refresh = () => {
  pageNum.value = 1
  finished.value = false
  loadList()
}

const goDetail = (activity) => {
  router.push({
    path: `/seckill/detail/${activity.id}`
  })
}

const parseTime = (value) => {
  if (!value) return 0
  const time = new Date(String(value).replace(/-/g, '/')).getTime()
  return Number.isNaN(time) ? 0 : time
}

const padTime = (value) => String(value).padStart(2, '0')

const getActivityStatus = (activity) => {
  const start = parseTime(activity.startTime)
  const end = parseTime(activity.endTime)
  if (start && nowTime.value < start) return '未开始'
  if (end && nowTime.value > end) return '已结束'
  return '进行中'
}

const getActivityCountdown = (activity) => {
  const start = parseTime(activity.startTime)
  const end = parseTime(activity.endTime)
  const target = start && nowTime.value < start ? start : end
  const diff = Math.max(0, (target || nowTime.value) - nowTime.value)
  const hours = Math.floor(diff / 1000 / 60 / 60)
  return {
    hours: hours > 99 ? '99+' : padTime(hours),
    minutes: padTime(Math.floor(diff / 1000 / 60) % 60),
    seconds: padTime(Math.floor(diff / 1000) % 60)
  }
}

const getActivityTheme = (activity) => {
  const themes = ['seckill-card--blue', 'seckill-card--peach', 'seckill-card--cream']
  const index = activityList.value.findIndex((item) => item.id === activity.id)
  return themes[Math.max(index, 0) % themes.length]
}

const getActivityKicker = (activity) => {
  return getActivityStatus(activity) === '未开始' ? 'NEXT FLASH DROP' : 'FLASH DROP'
}

const getActivityTitle = (activity) => {
  if (activity.name) return activity.name
  return getActivityStatus(activity) === '未开始' ? '下一场秒杀' : '限时秒杀'
}

const getActivityBrief = (activity) => {
  if (getActivityStatus(activity) === '未开始') return '准点开抢'
  if (getActivityStatus(activity) === '已结束') return '本场活动已结束'
  return '爆款限量开抢'
}

const getActivityCountdownLabel = (activity) => {
  return getActivityStatus(activity) === '未开始' ? '距开始' : '距结束'
}

const getActivityAction = (activity) => {
  if (getActivityStatus(activity) === '未开始') return '查看预告'
  if (getActivityStatus(activity) === '已结束') return '查看活动'
  return '立即开抢'
}

onMounted(() => {
  loadList()
  tickTimer = window.setInterval(() => {
    nowTime.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (tickTimer) {
    window.clearInterval(tickTimer)
  }
})
</script>

<style lang="scss" scoped>
.seckill {
  --seckill-primary: #0066cc;
  --seckill-focus: #0071e3;
  --seckill-canvas: #ffffff;
  --seckill-canvas-parchment: #f5f5f7;
  --seckill-tile-dark: #272729;
  --seckill-ink: #1d1d1f;
  --seckill-muted: #7a7a7a;
  --seckill-hairline: #e0e0e0;

  min-height: 100vh;
  color: var(--seckill-ink);
  background: var(--seckill-canvas-parchment);
  font-family: "SF Pro Display", "SF Pro Text", system-ui, -apple-system, BlinkMacSystemFont, sans-serif;
}

.seckill__body {
  width: 100%;
  max-width: var(--wb-content-width);
  margin: 0 auto;
  padding: 0 18px 64px;
}

.seckill__hero {
  position: relative;
  margin: 0 -18px;
  min-height: 430px;
  padding: 60px 28px 218px;
  overflow: hidden;
  color: var(--seckill-ink);
  text-align: center;
  background:
    radial-gradient(circle at 80% 28%, rgba(255, 255, 255, 0.96) 0 22%, transparent 42%),
    linear-gradient(135deg, #fff8f2 0%, #ffffff 46%, #edf5ff 100%);
}

.seckill__eyebrow {
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

.seckill__hero h1 {
  margin: 16px 0 10px;
  font-size: 62px;
  font-weight: 600;
  line-height: 1.07;
  letter-spacing: -0.01em;
}

.seckill__hero p {
  max-width: 560px;
  margin: 0 auto;
  color: var(--seckill-muted);
  font-size: 30px;
  line-height: 1.47;
  letter-spacing: -0.01em;
}

.seckill__hero-device {
  position: absolute;
  left: 50%;
  bottom: 0;
  width: 430px;
  height: 218px;
  transform: translateX(-50%);
}

.seckill__hero-device i {
  position: absolute;
  display: block;
}

.seckill__hero-stage {
  left: 56px;
  right: 20px;
  bottom: 0;
  height: 92px;
  border-radius: 50% 50% 0 0;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(224, 234, 245, 0.78));
}

.seckill__hero-bag {
  right: 42px;
  bottom: 54px;
  width: 114px;
  height: 160px;
  border: 1px solid rgba(29, 29, 31, 0.08);
  border-radius: 22px;
  background:
    linear-gradient(90deg, rgba(29, 29, 31, 0.05) 1px, transparent 1px) 0 0 / 15px 100%,
    linear-gradient(180deg, #fff8ef 0%, #ead5bf 100%);
}

.seckill__hero-bag::before {
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

.seckill__hero-earbuds {
  right: 132px;
  bottom: 58px;
  width: 100px;
  height: 82px;
  border-radius: 32px 32px 24px 24px;
  background: #ffffff;
  border: 1px solid rgba(29, 29, 31, 0.08);
}

.seckill__hero-earbuds::before,
.seckill__hero-earbuds::after {
  position: absolute;
  top: 15px;
  width: 26px;
  height: 36px;
  content: '';
  background: #edf3fb;
  border-radius: 999px;
}

.seckill__hero-earbuds::before {
  left: 21px;
}

.seckill__hero-earbuds::after {
  right: 21px;
}

.seckill__hero-watch {
  right: 222px;
  bottom: 60px;
  width: 78px;
  height: 112px;
  border: 8px solid #decbbc;
  border-radius: 24px;
  background: #1d1d1f;
}

.seckill__hero-watch::before {
  position: absolute;
  inset: 16px 12px;
  content: '';
  border-radius: 50%;
  background: radial-gradient(circle, #ff5a3c 0 18%, #f5f5f7 19% 22%, transparent 23%);
}

.seckill-card {
  position: relative;
  display: flex;
  min-height: 380px;
  margin: 22px 0 0;
  padding: 30px 26px;
  overflow: hidden;
  color: var(--seckill-ink);
  background: var(--seckill-canvas);
  border: 1px solid rgba(0, 0, 0, 0.04);
  border-radius: 28px;
  box-shadow: none;
  transition: transform 0.16s ease;
}

.seckill-card::before {
  position: absolute;
  inset: 0;
  content: '';
  pointer-events: none;
}

.seckill-card--blue {
  background: linear-gradient(135deg, #ffffff 0%, #f6f9ff 42%, #e9f2ff 100%);
}

.seckill-card--blue::before {
  background: linear-gradient(122deg, transparent 0 58%, rgba(0, 102, 204, 0.08) 58% 72%, transparent 72%);
}

.seckill-card--peach {
  background: linear-gradient(135deg, #fff8f2 0%, #ffffff 48%, #ffe5d7 100%);
}

.seckill-card--peach::before {
  background: linear-gradient(122deg, transparent 0 56%, rgba(255, 90, 60, 0.08) 56% 70%, transparent 70%);
}

.seckill-card--cream {
  background: linear-gradient(135deg, #fffaf2 0%, #ffffff 48%, #f2eadc 100%);
}

.seckill-card--cream::before {
  background: linear-gradient(122deg, transparent 0 56%, rgba(180, 130, 72, 0.1) 56% 70%, transparent 70%);
}

.seckill-card:active {
  transform: scale(0.98);
}

.seckill-card__status {
  position: absolute;
  top: 24px;
  right: 22px;
  z-index: 2;
  display: inline-flex;
  min-height: 48px;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  color: var(--seckill-primary);
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(0, 102, 204, 0.16);
  border-radius: 999px;
  font-size: 28px;
  font-weight: 600;
  line-height: 1;
}

.seckill-card__status::before {
  width: 10px;
  height: 10px;
  content: '';
  background: currentColor;
  border-radius: 50%;
}

.seckill-card__status--muted {
  color: var(--seckill-muted);
  border-color: rgba(0, 0, 0, 0.06);
}

.seckill-card__copy {
  position: relative;
  z-index: 2;
  width: 58%;
}

.seckill-card__kicker {
  margin: 0 0 16px;
  color: var(--seckill-primary);
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 0.08em;
}

.seckill-card h2 {
  margin: 0;
  font-size: 52px;
  font-weight: 600;
  line-height: 1.06;
  letter-spacing: -0.01em;
}

.seckill-card p:not(.seckill-card__kicker) {
  margin: 12px 0 0;
  color: var(--seckill-muted);
  font-size: 28px;
  line-height: 1.42;
}

.seckill-card__countdown {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 26px;
}

.seckill-card__countdown span {
  color: var(--seckill-muted);
  font-size: 28px;
  line-height: 1;
}

.seckill-card__countdown b {
  display: grid;
  min-width: 54px;
  height: 52px;
  place-items: center;
  color: var(--seckill-ink);
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 10px;
  font-size: 34px;
  font-weight: 700;
  line-height: 1;
}

.seckill-card__countdown i {
  color: var(--seckill-muted);
  font-size: 22px;
  font-style: normal;
  line-height: 1;
}

.seckill-card__action {
  display: inline-flex;
  min-height: 60px;
  align-items: center;
  gap: 10px;
  margin-top: 28px;
  padding: 0 26px;
  color: #ffffff;
  background: var(--seckill-primary);
  border-radius: 999px;
  font-size: 28px;
  font-weight: 600;
  line-height: 1;
  transition: transform 0.16s ease;
}

.seckill-card__action :deep(.van-icon) {
  width: 30px;
  height: 30px;
  display: inline-grid;
  place-items: center;
  color: var(--seckill-primary);
  background: #ffffff;
  border-radius: 50%;
  font-size: 20px;
}

.seckill-card__action:active {
  transform: scale(0.95);
}

.seckill-card__visual {
  position: absolute;
  right: -6px;
  bottom: 0;
  z-index: 1;
  width: 306px;
  height: 250px;
  pointer-events: none;
}

.seckill-card__visual i {
  position: absolute;
  display: block;
}

.seckill-card__stage {
  left: 28px;
  right: 0;
  bottom: 0;
  height: 90px;
  border-radius: 50% 50% 0 0;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(224, 234, 245, 0.74));
}

.seckill-card__phone {
  right: 118px;
  bottom: 64px;
  width: 76px;
  height: 142px;
  border-radius: 22px;
  background: linear-gradient(160deg, #b7cef0 0%, #7fa7d8 100%);
  border: 1px solid rgba(29, 29, 31, 0.08);
}

.seckill-card__phone::before,
.seckill-card__phone::after {
  position: absolute;
  left: 16px;
  width: 22px;
  height: 22px;
  content: '';
  background: #1d1d1f;
  border: 4px solid rgba(255, 255, 255, 0.42);
  border-radius: 50%;
}

.seckill-card__phone::before {
  top: 16px;
}

.seckill-card__phone::after {
  top: 46px;
}

.seckill-card__earbuds {
  right: 66px;
  bottom: 58px;
  width: 96px;
  height: 80px;
  border-radius: 32px 32px 24px 24px;
  background: #ffffff;
  border: 1px solid rgba(29, 29, 31, 0.08);
}

.seckill-card__earbuds::before,
.seckill-card__earbuds::after {
  position: absolute;
  top: 15px;
  width: 25px;
  height: 35px;
  content: '';
  background: #edf3fb;
  border-radius: 999px;
}

.seckill-card__earbuds::before {
  left: 20px;
}

.seckill-card__earbuds::after {
  right: 20px;
}

.seckill-card__speaker {
  right: -12px;
  bottom: 50px;
  width: 128px;
  height: 74px;
  border-radius: 18px;
  background: linear-gradient(135deg, #4b70aa, #173c71);
}

.seckill-card__speaker::before {
  position: absolute;
  inset: 18px 24px;
  content: '';
  border-radius: 999px;
  border: 4px solid rgba(255, 255, 255, 0.22);
}

@media (max-width: 419px) {
  .seckill__hero {
    min-height: 400px;
    padding: 46px 22px 202px;
  }

  .seckill__hero h1 {
    font-size: 54px;
  }

  .seckill__hero p {
    font-size: 28px;
  }

  .seckill__hero-device {
    width: 390px;
    transform: translateX(-50%) scale(.86);
    transform-origin: center bottom;
  }

  .seckill-card {
    min-height: 360px;
    padding: 28px 20px;
  }

  .seckill-card__copy {
    width: 62%;
  }

  .seckill-card h2 {
    font-size: 46px;
  }

  .seckill-card__countdown {
    gap: 6px;
  }

  .seckill-card__countdown b {
    min-width: 48px;
    height: 48px;
    font-size: 30px;
  }

  .seckill-card__visual {
    right: -66px;
    transform: scale(.82);
    transform-origin: right bottom;
  }
}
</style>
