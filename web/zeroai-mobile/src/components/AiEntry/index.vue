<template>
  <div class="ai-entry" @click="handleClick">
    <span class="ai-entry__dot" />
    <van-icon name="chat-o" class="ai-entry__icon" />
    <span class="ai-entry__text">AI 导购</span>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

/** 进入 AI 对话页；用 name 跳转，避免路径硬编码。 */
const handleClick = () => {
  router.push({ name: 'AiAssistant' })
}
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
