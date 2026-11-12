<template>
  <div
    ref="entryRef"
    class="ai-entry"
    :class="{ 'is-dragging': dragging, 'is-settling': settling }"
    :style="entryStyle"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
    @click="onClick"
  >
    <span class="ai-entry__dot" />
    <van-icon name="chat-o" class="ai-entry__icon" />
    <span class="ai-entry__text">AI 导购</span>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

/** 记住上次拖到的位置，下次进入沿用。 */
const POSITION_KEY = 'zeroai.aiEntry.position'

/** 位移超过该阈值才算拖动，否则视为点击，避免手指轻微抖动导致点不动。 */
const DRAG_THRESHOLD = 6

/** 贴边留白，保证按钮不压住屏幕边缘与安全区。 */
const EDGE_GAP = 12

/** 底部预留：tabbar 高度 + 安全区，避免拖到底部盖住导航。 */
const BOTTOM_RESERVED = 110

/** 顶部预留：导航栏区域，避免按钮飘到状态栏里。 */
const TOP_RESERVED = 60

/** 贴边动画时长，与 CSS 里的 transition 保持一致。 */
const SETTLE_MS = 220

const entryRef = ref(null)
const dragging = ref(false)
const settling = ref(false)

/**
 * 是否已经用绝对坐标定位。
 * false 时仍用 CSS 里的 right/bottom 默认位置，避免首次渲染跳一下。
 */
const floating = ref(false)

const pos = reactive({ left: 0, top: 0 })

/** 拖动过程中的临时状态，不需要响应式。 */
let startX = 0
let startY = 0
let originLeft = 0
let originTop = 0
let pointerId = null
/** 本次交互是否发生过拖动：用来抑制拖动结束后误触发的点击。 */
let moved = false
let settleTimer = null
let movedTimer = null
let size = { width: 0, height: 0 }

const entryStyle = computed(() => {
  if (!floating.value) {
    return {}
  }
  return {
    left: `${pos.left}px`,
    top: `${pos.top}px`,
    right: 'auto',
    bottom: 'auto'
  }
})

/**
 * 把坐标限制在可视区域内。
 *
 * @param {number} left 期望的左边距
 * @param {number} top  期望的上边距
 * @returns {{left: number, top: number}} 收敛后的坐标
 */
const clampPosition = (left, top) => {
  const viewWidth = window.innerWidth || document.documentElement.clientWidth
  const viewHeight = window.innerHeight || document.documentElement.clientHeight
  const maxLeft = Math.max(EDGE_GAP, viewWidth - size.width - EDGE_GAP)
  const maxTop = Math.max(TOP_RESERVED, viewHeight - size.height - BOTTOM_RESERVED)
  return {
    left: Math.min(Math.max(EDGE_GAP, left), maxLeft),
    top: Math.min(Math.max(TOP_RESERVED, top), maxTop)
  }
}

/** 读取并校验本地保存的位置，非法数据直接忽略。 */
const readSavedPosition = () => {
  try {
    const raw = window.localStorage.getItem(POSITION_KEY)
    if (!raw) {
      return null
    }
    const saved = JSON.parse(raw)
    if (typeof saved.left !== 'number' || typeof saved.top !== 'number') {
      return null
    }
    return saved
  } catch (e) {
    return null
  }
}

/** 记录按钮尺寸，拖动与收敛都依赖它。 */
const measure = () => {
  const el = entryRef.value
  if (!el) {
    return
  }
  const rect = el.getBoundingClientRect()
  size = { width: rect.width, height: rect.height }
}

/** 把当前位置写回本地，刷新后仍在原处。 */
const persistPosition = () => {
  try {
    window.localStorage.setItem(
      POSITION_KEY,
      JSON.stringify({ left: Math.round(pos.left), top: Math.round(pos.top) })
    )
  } catch (e) {
    // 隐私模式下 localStorage 可能不可写，位置记忆失败不影响使用
  }
}

/**
 * 松手后贴到最近的左右边缘。
 *
 * 悬浮按钮的价值是「随手可点但不挡路」，停在屏幕中间最容易压住商品卡片的按钮，
 * 所以水平方向吸附到边缘，垂直方向保持用户拖到的位置。
 */
const snapToEdge = () => {
  const viewWidth = window.innerWidth || document.documentElement.clientWidth
  const centerX = pos.left + size.width / 2
  const target = centerX < viewWidth / 2 ? EDGE_GAP : viewWidth - size.width - EDGE_GAP
  if (Math.abs(target - pos.left) < 1) {
    return
  }
  settling.value = true
  pos.left = target
  clearTimeout(settleTimer)
  settleTimer = setTimeout(() => {
    settling.value = false
  }, SETTLE_MS)
}

const onPointerDown = (event) => {
  // 只响应主键与触摸/触控笔，右键拖动不处理
  if (event.pointerType === 'mouse' && event.button !== 0) {
    return
  }
  const el = entryRef.value
  if (!el) {
    return
  }
  measure()
  const rect = el.getBoundingClientRect()
  startX = event.clientX
  startY = event.clientY
  originLeft = rect.left
  originTop = rect.top
  pointerId = event.pointerId
  moved = false
  // 捕获指针：手指滑出按钮范围也能继续跟随
  if (el.setPointerCapture) {
    try {
      el.setPointerCapture(event.pointerId)
    } catch (e) {
      // 个别浏览器在 pointerdown 之外调用会抛错，忽略即可
    }
  }
}

const onPointerMove = (event) => {
  if (pointerId === null || event.pointerId !== pointerId) {
    return
  }
  const dx = event.clientX - startX
  const dy = event.clientY - startY

  if (!dragging.value) {
    if (Math.abs(dx) < DRAG_THRESHOLD && Math.abs(dy) < DRAG_THRESHOLD) {
      return
    }
    dragging.value = true
    floating.value = true
    settling.value = false
  }

  // 拖动期间阻止页面滚动与选中文本
  event.preventDefault()
  const next = clampPosition(originLeft + dx, originTop + dy)
  pos.left = next.left
  pos.top = next.top
}

const onPointerUp = () => {
  if (pointerId === null) {
    return
  }
  const el = entryRef.value
  if (el && el.releasePointerCapture) {
    try {
      el.releasePointerCapture(pointerId)
    } catch (e) {
      // 指针可能已被系统释放，忽略
    }
  }
  pointerId = null

  if (!dragging.value) {
    return
  }
  dragging.value = false
  // 拖动结束不应触发跳转。这里同时用定时器兜底：某些浏览器在 preventDefault
  // 之后不再派发 click，标记若不重置会吞掉下一次正常点击。
  moved = true
  clearTimeout(movedTimer)
  movedTimer = setTimeout(() => {
    moved = false
  }, 400)
  snapToEdge()
  persistPosition()
}

/**
 * 点击进入对话页。
 * 拖动位移超过阈值时抑制这次点击，否则「拖完手一松」就会误跳页面。
 */
const onClick = () => {
  if (moved) {
    moved = false
    return
  }
  router.push({ name: 'AiAssistant' })
}

/** 窗口尺寸变化（横竖屏切换、软键盘弹出）后重新收敛，避免按钮跑到屏幕外。 */
const onResize = () => {
  if (!floating.value) {
    return
  }
  measure()
  const next = clampPosition(pos.left, pos.top)
  pos.left = next.left
  pos.top = next.top
}

onMounted(() => {
  measure()
  const saved = readSavedPosition()
  if (saved) {
    const next = clampPosition(saved.left, saved.top)
    pos.left = next.left
    pos.top = next.top
    floating.value = true
  }
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  clearTimeout(settleTimer)
  clearTimeout(movedTimer)
})
</script>

<style lang="scss" scoped>
.ai-entry {
  position: fixed;
  right: 24px;
  bottom: calc(env(safe-area-inset-bottom, 0px) + 150px);
  z-index: 90;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  padding: 16px 28px 16px 22px;
  border-radius: 999px;
  background: linear-gradient(135deg, #0071e3 0%, #42a5ff 100%);
  color: #fff;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.08px;
  box-shadow: 0 18px 40px rgba(0, 113, 227, 0.32);
  transition: transform 0.2s ease;
  /* 拖动需要完全接管手势：不滚页面、不选中文字、不触发浏览器手势 */
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
  cursor: grab;

  /* 贴边收尾给一点过渡；拖动过程中必须无延迟跟随手指，所以按状态切换 */
  &.is-settling {
    transition: left 0.2s ease, top 0.2s ease, transform 0.2s ease;
  }

  &.is-dragging {
    cursor: grabbing;
    /* 拖动时放大一点，让用户确认「抓住了」 */
    transform: scale(1.06);
    box-shadow: 0 22px 48px rgba(0, 113, 227, 0.4);

    &:active {
      transform: scale(1.06);
    }
  }

  &:active {
    transform: scale(0.96);
  }

  &__icon {
    font-size: 30px;
  }

  /* 呼吸点：提示「在线可对话」，不做过度动效 */
  &__dot {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: #35d07f;
    box-shadow: 0 0 0 6px rgba(53, 208, 127, 0.24);
  }
}
</style>
