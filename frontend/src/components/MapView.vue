<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, defineProps } from 'vue'
import { loadAmap } from '@/lib/loadAmap'

type EventItem = {
  id: string
  ts: string
  level: 'info'|'minor'|'major'|'critical'
  source: string
  lat: number
  lng: number
  summary: string
}

const props = defineProps<{ events: EventItem[]; highlightId?: string }>()

const container = ref<HTMLDivElement|null>(null)
let map: any = null
let markers: Record<string, any> = {}
const loading = ref(true)
const error = ref<string| null>(null)

function levelColor(level: string) {
  switch (level) {
    case 'critical': return '#ff4d4f'
    case 'major': return '#fa8c16'
    case 'minor': return '#fadb14'
    default: return '#40a9ff'
  }
}

function renderMarkers() {
  if (!map || !(window as any).AMap) return
  Object.values(markers).forEach(m => m.setMap && m.setMap(null))
  markers = {}
  const AMap = (window as any).AMap
  props.events.forEach(e => {
    const el = document.createElement('div')
    el.style.width = '10px'
    el.style.height = '10px'
    el.style.borderRadius = '50%'
    el.style.background = levelColor(e.level)
    el.style.boxShadow = e.id === props.highlightId ? '0 0 0 4px rgba(64,169,255,0.4)' : 'none'
    el.title = `${e.summary}`
    const m = new AMap.Marker({
      position: [e.lng, e.lat],
      anchor: 'center',
      offset: new AMap.Pixel(0, 0),
      content: el
    })
    m.setMap(map)
    markers[e.id] = m
  })
}

onMounted(async () => {
  try {
    const AMap = await loadAmap()
    if (!container.value) return
    map = new AMap.Map(container.value, {
      zoom: 12,
      center: [117.125, 34.125],
      viewMode: '3D'
    })
    map.addControl(new AMap.Scale())
    map.addControl(new AMap.ToolBar())
    loading.value = false
    renderMarkers()
  } catch (e: any) {
    error.value = e?.message || String(e)
    loading.value = false
  }
})

onBeforeUnmount(() => {
  try { if (map && map.destroy) map.destroy() } catch {}
})

watch(() => [props.events, props.highlightId], () => renderMarkers(), { deep: true })
</script>

<template>
  <div style="padding:8px;height:100%">
    <div v-if="error" style="height:100%;display:flex;align-items:center;justify-content:center;color:#ef4444;border:1px dashed #1b2a44;border-radius:8px;">
      {{ error }}
    </div>
    <div v-else ref="container" style="height:calc(100vh - 56px - 140px);border:1px solid #1b2a44;border-radius:8px;overflow:hidden">
      <div v-if="loading" style="height:100%;display:flex;align-items:center;justify-content:center;color:#9fb3c8;">地图加载中…</div>
    </div>
  </div>
  
</template>

<style scoped>
</style>
