<script setup lang="ts">
import { alarms } from '@/store/alerts'
import type { Alarm } from '@/store/alerts'

function levelColor(a: Alarm) {
  // use theme variables for consistency
  if (a.level === 'critical') return 'var(--danger-color)'
  if (a.level === 'major') return 'var(--warning-color)'
  if (a.level === 'minor') return 'var(--info-color)'
  return 'var(--text-muted)'
}
</script>

<template>
  <div class="panel-alerts">
    <a-card v-for="a in alarms" :key="a.id" size="small" :bordered="true" :bodyStyle="{padding:'8px'}" class="alert-card">
      <div class="alert-row">
        <span class="level-bar" :style="{ background: levelColor(a) }"></span>
        <div class="alert-main">
          <div class="alert-head">
            <span>{{ a.place }} · {{ a.source }}</span>
            <span class="muted">{{ a.time }}</span>
          </div>
          <div class="alert-summary">{{ a.summary }}</div>
        </div>
        <a-button type="link" size="small" class="link-accent">处理</a-button>
      </div>
    </a-card>
    <div v-if="alarms.length===0" class="muted empty">暂无告警</div>
  </div>
</template>

<style scoped>
.panel-alerts { display:flex; flex-direction:column; gap:8px; flex:1; overflow:hidden; }
.panel-alerts { max-height: 100%; overflow-y: auto; padding-right:4px; }
.panel-alerts:hover { scrollbar-width: thin; }
.panel-alerts::-webkit-scrollbar { width: 6px; }
.panel-alerts::-webkit-scrollbar-thumb { background: rgba(79, 121, 191, 0.6); border-radius: 3px; }
.alert-card { background: var(--panel-bg); border-color: var(--panel-border); color: var(--text-color); }
.alert-row { display:flex; gap:8px; align-items:center; cursor:pointer; }
.level-bar { width:6px; height:32px; display:inline-block; }
.alert-main { flex:1; }
.alert-head { display:flex; justify-content:space-between; }
.alert-summary { color: var(--text-color); }
.link-accent { color: var(--accent-color) !important; }
.empty { text-align:center; padding:16px; }
</style>
