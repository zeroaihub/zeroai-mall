<template>
  <div id="app">
    <keep-alive>
      <router-view v-if="$route.meta.keepAlive" :key="key" />
    </keep-alive>
    <router-view v-if="!$route.meta.keepAlive" :key="key" />

    <tabbar v-if="$route.meta.showTab" />

    <!-- AI 导购入口：只在带 tabbar 的主页面出现，不干扰列表页与详情页的操作区 -->
    <ai-entry v-if="$route.meta.showTab" />
  </div>
</template>

<script>
import Tabbar from './components/Tabbar'
import AiEntry from './components/AiEntry/index.vue'

export default {
  components: {
    Tabbar,
    AiEntry
  },
  computed: {
    key() {
      return this.$route.fullPath
    }
  }
}
</script>
<style scoped>
  #app {
    position: relative;
    font-size: 28px;
  }
</style>
