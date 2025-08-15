<script setup lang="ts">
import { defineEmits, defineProps, onMounted, onUnmounted, ref } from 'vue'

type EventItem = {
  id: string
  ts: string
  level: 'info'|'minor'|'major'|'critical'
  source: 'camera'|'radar'|'seismic'|'imsi'|'drone'|'fusion'
  summary: string
}

const props = defineProps<{ events: EventItem[] }>()
const emit = defineEmits<{ (e:'select', id: string): void }>()
const activeId = ref('')

function levelTag(l: EventItem['level']) {
  const map: Record<string, { color: string; text: string }> = {
    info: { color: 'blue', text: 'Info' },
    minor: { color: 'gold', text: 'Minor' },
    major: { color: 'orange', text: 'Major' },
    critical: { color: 'red', text: 'Critical' },
  }
  return map[l]
}

function select(id: string) {
  activeId.value = id
  emit('select', id)
}

function onKey(e: KeyboardEvent) {
  if (['a','d','u','b','A','D','U','B'].includes(e.key)) {
    // 这里只做提示占位，实际应触发工单/派机/旁路等动作
    // 可改为 emit 对应 action
    console.log('快捷键', e.key)
  }
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div>
    <div style="padding:8px;color:#9fb3c8;display:flex;justify-content:space-between;align-items:center">
      <div>告警队列</div>
      <div style="font-size:12px;opacity:.8">快捷键：A 接单 / D 指派 / U 派机 / B 旁路</div>
    </div>
    <div style="height:calc(100vh - 56px - 160px);overflow:auto;padding:0 4px 8px 4px">
      <a-list size="small" :data-source="props.events" :renderItem="(item:any)=>null">
        <template #renderItem="{ item }">
          <a-list-item :style="{background: activeId===item.id?'#102038':'#0e1628', border:'1px solid #1b2a44',margin:'6px 0',borderRadius:'6px',cursor:'pointer'}" @click="select(item.id)">
            <a-list-item-meta>
              <template #title>
                <div style="display:flex;align-items:center;gap:8px;color:#dfe6ef">
                  <a-tag :color="levelTag(item.level).color">{{ levelTag(item.level).text }}</a-tag>
                  <span style="opacity:.9">{{ item.summary }}</span>
                </div>
              </template>
              <template #description>
                <div style="display:flex;justify-content:space-between;color:#9fb3c8">
                  <span>{{ item.source }} · {{ new Date(item.ts).toLocaleTimeString() }}</span>
                  <span>地点#{{ item.id.slice(-3) }}</span>
                </div>
              </template>
            </a-list-item-meta>
            <template #actions>
              <a @click.stop>接单</a>
              <a @click.stop>指派</a>
              <a @click.stop>派机</a>
              <a @click.stop>旁路</a>
            </template>
          </a-list-item>
        </template>
      </a-list>
    </div>
  </div>
</template>

<style scoped>
</style>

