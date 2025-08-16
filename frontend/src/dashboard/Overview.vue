<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { loadAmap } from '@/lib/loadAmap'
import { nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort } from '@/store/config'

type Cam = { id: string, name: string, lat?: number, lng?: number }
type Alarm = { id: string, level: 'info'|'minor'|'major'|'critical', source: string, place: string, time: string, summary: string, deviceId?: string }

// Devices (map markers)
const cameras = ref<Cam[]>([
  { id: 'cam401', name: '北门401', lat: 34.1239, lng: 117.1231 },
  { id: 'cam402', name: '北门402', lat: 34.1237, lng: 117.1233 },
  { id: 'cam403', name: '南侧403', lat: 34.1229, lng: 117.1239 }
])
const selectedCamId = ref<string>('cam402')
const mapEl = ref<HTMLDivElement|null>(null)
const map = ref<any>(null)
const mapLoading = ref(true)
const mapError = ref<string | null>(null)


function onPickAlarm(a: Alarm) {
  if (a.deviceId) {
    selectedCamId.value = a.deviceId
  }
}

onMounted(async () => {
  // auto select first camera
  if (cameras.value.length && !selectedCamId.value) selectedCamId.value = cameras.value[0].id
  // init map (AMap)
  try {
    const AMap = await loadAmap()
    if (!mapEl.value) return
    map.value = new AMap.Map(mapEl.value, {
      center: [cameras.value[0].lng || 117.1233, cameras.value[0].lat || 34.1237],
      zoom: 16,
      viewMode: '3D'
    })
    map.value.addControl(new AMap.ControlBar())
    map.value.addControl(new AMap.Scale())
    map.value.addControl(new AMap.ToolBar())
    // add camera markers
    cameras.value.forEach(cam => {
      if (cam.lng && cam.lat) {
        const el = document.createElement('div')
        el.className = 'marker-camera'
        el.title = cam.name
        el.addEventListener('click', () => { selectedCamId.value = cam.id })
        const marker = new AMap.Marker({ position: [cam.lng, cam.lat], anchor: 'center', content: el })
        marker.setMap(map.value)
      }
    })
    mapLoading.value = false
  } catch (e: any) {
    mapError.value = e?.message || String(e)
    mapLoading.value = false
  }
})

onBeforeUnmount(() => {
  try { map.value && map.value.destroy && map.value.destroy() } catch (_) {}
})

// keep only map logic here; alerts are handled globally in App via store

  
</script>

<template>
  <a-layout style="height: calc(100vh - 64px); background: var(--bg-color); color: #000000;">
    <a-layout>
      <!-- Center Map -->
      <a-layout-content style="position:relative;">
        <div ref="mapEl" class="mapwrap" />
        <div class="map-overlay">
          <div style="display:flex; gap:8px; flex-wrap:wrap;">
            <a-tag v-for="c in cameras" :key="c.id" color="#c9924d" @click="selectedCamId=c.id" style="cursor:pointer;">{{ c.name }}</a-tag>
          </div>
        </div>
      </a-layout-content>

      
    </a-layout>

    <!-- No bottom console on main screen -->
  </a-layout>
</template>

<style scoped>
.ant-card { background: #ffffff; border-color: #dddddd; }
.mapwrap { position:absolute; inset:0; }
.map-overlay { position:absolute; left:12px; top:12px; background: rgba(240,240,240,0.9); border:1px solid #dddddd; padding:8px; border-radius:6px; }
.marker-camera { width:12px; height:12px; border-radius:50%; background:#c9924d; border:2px solid #a87638; box-shadow:0 0 0 2px rgba(201,146,77,0.35); cursor:pointer; }
</style>
