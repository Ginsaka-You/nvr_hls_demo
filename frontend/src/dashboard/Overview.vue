<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { loadAmap } from '@/lib/loadAmap'

type Cam = { id: string, name: string, lat?: number, lng?: number }
type Alarm = { id: string, level: 'info'|'minor'|'major'|'critical', source: string, place: string, time: string, summary: string, deviceId?: string }

// Mock devices and alarms (can be wired to WS later)
const cameras = ref<Cam[]>([
  { id: 'cam401', name: '北门401', lat: 34.1239, lng: 117.1231 },
  { id: 'cam402', name: '北门402', lat: 34.1237, lng: 117.1233 },
  { id: 'cam403', name: '南侧403', lat: 34.1229, lng: 117.1239 }
])
const alarms = ref<Alarm[]>([
  { id: 'a1', level: 'major', source: 'radar', place: '北门外侧', time: new Date().toLocaleTimeString(), summary: '移动目标靠近', deviceId: 'cam402' },
  { id: 'a2', level: 'minor', source: 'seismic', place: '西侧围栏', time: new Date().toLocaleTimeString(), summary: '震动触发' },
  { id: 'a3', level: 'critical', source: 'fusion', place: '北门', time: new Date().toLocaleTimeString(), summary: '多源命中（人形+雷达+IMSI）', deviceId: 'cam402' }
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
</script>

<template>
  <a-layout style="height: calc(100vh - 64px); background: #0b1220; color: #dbe4ff;">
    <a-layout>
      <!-- Center Map -->
      <a-layout-content style="background:#0b1220; position:relative;">
        <div ref="mapEl" class="mapwrap" />
        <div class="map-overlay">
          <div style="display:flex; gap:8px; flex-wrap:wrap;">
            <a-tag v-for="c in cameras" :key="c.id" color="#334155" @click="selectedCamId=c.id" style="cursor:pointer;">{{ c.name }}</a-tag>
          </div>
        </div>
      </a-layout-content>

      <!-- Right Alarm Panel -->
      <a-layout-sider width="360" style="background:#0b1220; border-left:1px solid #1e293b; padding:8px;">
        <div style="font-weight:600; margin:4px 0 8px;">告警队列</div>
        <div style="display:flex; flex-direction:column; gap:8px; max-height: calc(100vh - 80px); overflow:auto;">
          <a-card v-for="a in alarms" :key="a.id" size="small" :bordered="true" @click="onPickAlarm(a)"
                  :bodyStyle="{padding:'8px'}" style="cursor:pointer;">
            <div style="display:flex; gap:8px; align-items:center;">
              <span :style="{width:'6px', height:'32px', background:a.level==='critical'?'#ef4444':a.level==='major'?'#f59e0b':a.level==='minor'?'#eab308':'#60a5fa', display:'inline-block'}"></span>
              <div style="flex:1;">
                <div style="display:flex; justify-content:space-between;">
                  <span>{{ a.place }} · {{ a.source }}</span>
                  <span style="color:#94a3b8">{{ a.time }}</span>
                </div>
                <div style="color:#cbd5e1">{{ a.summary }}</div>
              </div>
              <a-button type="link" size="small">接单</a-button>
            </div>
          </a-card>
        </div>
      </a-layout-sider>
    </a-layout>

    <!-- No bottom console on main screen -->
  </a-layout>
</template>

<style scoped>
.ant-card { background: #0f172a; border-color: #1e293b; }
.mapwrap { position:absolute; inset:0; }
.map-overlay { position:absolute; left:12px; top:12px; background: rgba(15,23,42,0.7); border:1px solid #1e293b; padding:8px; border-radius:6px; }
.marker-camera { width:12px; height:12px; border-radius:50%; background:#22c55e; border:2px solid #064e3b; box-shadow:0 0 0 2px rgba(34,197,94,0.4); cursor:pointer; }
</style>
