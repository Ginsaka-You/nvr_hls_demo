<script setup lang="ts">
import { computed } from 'vue'
import { alarms } from '@/store/alerts'
import type { Alarm } from '@/store/alerts'

const props = defineProps<{
  items?: Alarm[]
}>()

const emit = defineEmits<{
  (event: 'preview', images: string[], index: number): void
}>()

const alertItems = computed(() => props.items ?? alarms.value)

function levelColor(a: Alarm) {
  // use theme variables for consistency
  if (a.level === 'critical') return 'var(--danger-color)'
  if (a.level === 'major') return 'var(--warning-color)'
  if (a.level === 'minor') return 'var(--info-color)'
  return 'var(--text-muted)'
}

function getAlarmSnapshots(alarm: Alarm): string[] {
  if (alarm.snapshots && alarm.snapshots.length) return alarm.snapshots
  return alarm.snapshotUrl ? [alarm.snapshotUrl] : []
}

function onPreview(alarm: Alarm, index = 0) {
  const images = getAlarmSnapshots(alarm)
  if (!images.length) return
  emit('preview', images, index)
}
</script>

<template>
  <div class="panel-alerts">
    <div v-for="a in alertItems" :key="a.id" class="task-item">
      <button v-if="getAlarmSnapshots(a).length" class="task-thumb-btn" type="button" @click="onPreview(a, 0)">
        <span class="task-thumb" :style="{ borderColor: levelColor(a) }">
          <img :src="getAlarmSnapshots(a)[0]" alt="snapshot" />
          <span v-if="getAlarmSnapshots(a).length > 1" class="thumb-count">+{{ getAlarmSnapshots(a).length - 1 }}</span>
        </span>
      </button>
      <div v-else class="task-thumb" :style="{ borderColor: levelColor(a) }">
        <div class="thumb-placeholder">暂无</div>
      </div>
      <div class="task-body">
        <div class="task-head">
          <span class="task-time">{{ a.time }}</span>
          <span class="task-type">{{ a.source }}</span>
          <span class="task-place">{{ a.place }}</span>
        </div>
        <div class="task-summary">{{ a.summary }}</div>
      </div>
      <a-button size="small" class="task-action">处置</a-button>
    </div>
    <div v-if="alertItems.length===0" class="muted empty">暂无告警</div>
  </div>
</template>

<style scoped>
.panel-alerts { display:flex; flex-direction:column; gap:10px; }
.task-item {
  position: relative;
  display:flex;
  gap:10px;
  align-items:center;
  padding:8px;
  border-radius:10px;
  border:1px solid rgba(0, 229, 255, 0.18);
  background: rgba(4, 9, 18, 0.65);
  transition: border-color 0.2s ease, background 0.2s ease;
}
.task-item::before {
  content: "";
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 2px;
  background: transparent;
  transition: background 0.2s ease;
}
.task-item:hover {
  background: rgba(0, 229, 255, 0.08);
  border-color: rgba(0, 229, 255, 0.45);
}
.task-item:hover::before { background: #00e5ff; }
.task-thumb {
  width: 60px;
  height: 60px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid rgba(255, 77, 79, 0.5);
  flex-shrink: 0;
  background: rgba(15, 23, 42, 0.8);
  display: flex;
  align-items: center;
  justify-content: center;
}
.task-thumb-btn {
  padding: 0;
  cursor: pointer;
  border: none;
  background: none;
  display: block;
}
.task-thumb-btn .task-thumb {
  position: relative;
}
.task-thumb-btn img { width: 100%; height: 100%; object-fit: cover; display: block; }
.thumb-count {
  position: absolute;
  right: 4px;
  bottom: 4px;
  background: rgba(0, 0, 0, 0.6);
  color: #e2f6ff;
  font-size: 11px;
  padding: 0 4px;
  border-radius: 10px;
}
.thumb-placeholder {
  font-size: 12px;
  color: #94a3b8;
}
.task-body { flex:1; min-width:0; display:flex; flex-direction:column; gap:4px; }
.task-head { display:flex; gap:6px; align-items:center; font-size:12px; color:#94a3b8; }
.task-time { font-family: "Roboto Mono", monospace; }
.task-type { color: #e2f6ff; font-weight: 600; }
.task-place { color: #94a3b8; }
.task-summary {
  color: #e2f6ff;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.task-action {
  border-radius: 999px;
  padding: 0 12px;
  height: 26px;
  background: rgba(0, 229, 255, 0.15);
  border: 1px solid rgba(0, 229, 255, 0.45);
  color: #7ee7ff;
  box-shadow: inset 0 0 8px rgba(0, 229, 255, 0.25);
}
.task-action:hover {
  color: #ffffff;
  border-color: rgba(0, 229, 255, 0.8);
  background: rgba(0, 229, 255, 0.3);
}
.empty { text-align:center; padding:16px; }
</style>
