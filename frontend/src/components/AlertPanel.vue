<script setup lang="ts">
import { alarms } from '@/store/alerts'
import type { Alarm } from '@/store/alerts'

function levelColor(a: Alarm) {
  if (a.level === 'critical') return '#d92c2c'
  if (a.level === 'major') return '#a87638'
  if (a.level === 'minor') return '#c9924d'
  return '#8a8a8a'
}
</script>

<template>
  <div class="no-scrollbar" style="display:flex; flex-direction:column; gap:8px; flex:1; overflow:hidden;">
    <a-card v-for="a in alarms" :key="a.id" size="small" :bordered="true" :bodyStyle="{padding:'8px'}" style="cursor:pointer;">
      <div style="display:flex; gap:8px; align-items:center;">
        <span :style="{width:'6px', height:'32px', background:levelColor(a), display:'inline-block'}"></span>
        <div style="flex:1;">
          <div style="display:flex; justify-content:space-between;">
            <span>{{ a.place }} · {{ a.source }}</span>
            <span style="color:#00000099">{{ a.time }}</span>
          </div>
          <div style="color:#000000">{{ a.summary }}</div>
        </div>
        <a-button type="link" size="small" style="color:#c9924d">接单</a-button>
      </div>
    </a-card>
    <div v-if="alarms.length===0" style="color:#00000099; text-align:center; padding:16px;">暂无告警</div>
  </div>
</template>

<style scoped>
</style>

