<script setup lang="ts">
import { computed, defineProps } from 'vue'

type EventItem = { ts: string; level: 'info'|'minor'|'major'|'critical' }
const props = defineProps<{ events: EventItem[] }>()

const buckets = computed(() => {
  // 近 30 分钟密度
  const now = Date.now()
  const arr = Array.from({ length: 30 }).map((_, i) => ({
    t: now - (29 - i) * 60000,
    count: 0,
    critical: 0,
  }))
  props.events.forEach(e => {
    const ts = new Date(e.ts).getTime()
    const idx = Math.floor((ts - (now - 30*60000)) / 60000)
    if (idx >=0 && idx < 30) {
      arr[idx].count += 1
      if (e.level === 'critical') arr[idx].critical += 1
    }
  })
  const max = Math.max(1, ...arr.map(a=>a.count))
  return arr.map(a => ({ ...a, h: Math.round(a.count / max * 36), hc: Math.round(a.critical / Math.max(1,a.count)*a.count / max * 36) }))
})
</script>

<template>
  <div style="display:flex;align-items:flex-end;gap:4px;height:48px;padding:4px;background:#0b1220;border:1px solid #1b2a44;border-radius:6px">
    <div v-for="(b,i) in buckets" :key="i" style="width:8px;background:#1b2a44;position:relative;border-radius:2px;overflow:hidden">
      <div :style="{position:'absolute',bottom:'0',left:'0',right:'0',height:b.h+'px',background:'#40a9ff'}"></div>
      <div :style="{position:'absolute',bottom:'0',left:'0',right:'0',height:b.hc+'px',background:'#ff4d4f'}"></div>
    </div>
  </div>
</template>

<style scoped>
</style>

