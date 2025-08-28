<script setup lang="ts">
import { computed } from 'vue'

const url = computed(() => {
  return import.meta.env.VITE_BIGDATA61_URL || '/bigdata/061/index.html'
})
</script>

<template>
  <a-layout style="min-height:calc(100vh - 64px);">
    <a-layout-content style="padding:12px; color: var(--text-color);">
      <div class="frame-card">
        <iframe :src="url" frameborder="0" class="frame" />
      </div>
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
.frame-card {
  background: var(--panel-bg);
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0,0,0,0.25);
  height: calc(100vh - 64px - 24px);
  overflow: hidden;
}
.frame { width: 100%; height: 100%; display: block; }
</style>

