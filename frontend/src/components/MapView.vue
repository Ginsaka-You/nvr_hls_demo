<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, defineProps } from 'vue'

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

function levelColor(level: string) {
  switch (level) {
    case 'critical': return '#ff4d4f'
    case 'major': return '#fa8c16'
    case 'minor': return '#fadb14'
    default: return '#40a9ff'
  }
}

function renderMarkers() {
  if (!map || !maplibre) return
  // remove old
  Object.values(markers).forEach(m => m.remove())
  markers = {}
  props.events.forEach(e => {
    const el = document.createElement('div')
    el.style.width = '10px'
    el.style.height = '10px'
    el.style.borderRadius = '50%'
    el.style.background = levelColor(e.level)
    el.style.boxShadow = e.id === props.highlightId ? '0 0 0 4px rgba(64,169,255,0.4)' : 'none'
    el.title = `${e.summary}`
    const m = new maplibre.Marker({ element: el }).setLngLat([e.lng, e.lat]).addTo(map)
    markers[e.id] = m
  })
}

onMounted(async () => {
  const maplibre = (window as any)?.maplibregl
  if (!maplibre || !container.value) return
  map = new maplibre.Map({
    container: container.value,
    style: 'https://demotiles.maplibre.org/style.json',
    center: [117.125, 34.125],
    zoom: 12,
    attributionControl: false
  })
  map.on('load', renderMarkers)
})

onBeforeUnmount(() => { if (map) map.remove() })

watch(() => [props.events, props.highlightId], () => renderMarkers(), { deep: true })
</script>

<template>
  <div style="padding:8px;height:100%">
    <div v-if="!($any(window).maplibregl)" style="height:100%;display:flex;align-items:center;justify-content:center;color:#9fb3c8;border:1px dashed #1b2a44;border-radius:8px;">
      <div>
        <div style="text-align:center;margin-bottom:8px">地图占位（未安装 maplibre-gl）</div>
        <div style="font-size:12px;opacity:0.8">安装后将渲染摄像头/IMSI/雷达/震动/无人机图层。</div>
      </div>
    </div>
    <div v-else ref="container" style="height:calc(100vh - 56px - 140px);border:1px solid #1b2a44;border-radius:8px;overflow:hidden"></div>
  </div>
  
</template>

<style scoped>
</style>
