<script setup lang="ts">
import { defineProps } from 'vue'
import VideoPlayer from './VideoPlayer.vue'
import type { StreamSource } from '@/types/stream'

const props = defineProps<{ mainSource?: StreamSource | null }>()
</script>

<template>
  <div>
    <!-- 主窗 + 辅窗栅格 -->
    <div style="display:grid;grid-template-columns:2fr 1fr;grid-template-rows:auto auto;gap:8px">
      <div style="grid-column:1 / 2;grid-row:1 / 3;background:#ffffff;border:1px solid #dddddd;border-radius:6px;padding:6px">
        <div style="color:#000000;margin-bottom:4px">主窗</div>
        <div v-if="props.mainSource">
          <VideoPlayer :source="props.mainSource" />
        </div>
        <div v-else style="height:360px;display:flex;align-items:center;justify-content:center;color:#00000099;background:#000;border-radius:4px">
          无主窗视频（点击右上“启动”接入流）
        </div>
      </div>
      <div v-for="i in 3" :key="i" style="background:#ffffff;border:1px solid #dddddd;border-radius:6px;padding:6px">
        <div style="color:#000000;margin-bottom:4px">辅窗 {{ i }}</div>
        <div style="height:172px;background:#000;border-radius:4px"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
</style>
