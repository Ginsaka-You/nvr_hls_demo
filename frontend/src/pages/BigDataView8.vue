<script setup lang="ts">
import { computed } from 'vue'

// 可通过 .env.local 配置自定义地址，例如：
// VITE_BIGDATA8_URL=https://your-internal-host/screen8/index.html
const url = computed(() => {
  // 默认使用本地拷贝
  return import.meta.env.VITE_BIGDATA8_URL || '/bigdata/008/index.html'
})
</script>

<template>
  <a-layout style="min-height:calc(100vh - 64px);">
    <a-layout-content style="padding:12px; color:#000;">
      <div class="frame-card">
        <iframe :src="url" frameborder="0" class="frame" />
      </div>
    </a-layout-content>
  </a-layout>
  
</template>

<style scoped>
.frame-card {
  background: #ffffff;
  border: 1px solid #dddddd;
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0,0,0,0.06);
  height: calc(100vh - 64px - 24px);
  overflow: hidden;
}
.frame { width: 100%; height: 100%; display: block; }
</style>
