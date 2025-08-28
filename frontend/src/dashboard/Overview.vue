<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import AlertPanel from '@/components/AlertPanel.vue'
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
    const satellite = new AMap.TileLayer.Satellite()
    const roadNet = new AMap.TileLayer.RoadNet()
    map.value = new AMap.Map(mapEl.value, {
      center: [cameras.value[0].lng || 117.1233, cameras.value[0].lat || 34.1237],
      zoom: 16,
      viewMode: '3D',
      layers: [satellite, roadNet]
    })
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
        <!-- 左侧三个悬浮信息框 -->
        <div class="left-panels">
          <div class="panel-card">
            <div class="panel-header">点位报警排名</div>
            <div class="panel-body">
              <div class="muted">暂无数据</div>
            </div>
          </div>
          <div class="panel-card">
            <div class="panel-header">人员处置次数统计</div>
            <div class="panel-body">
              <div class="muted">暂无数据</div>
            </div>
          </div>
          <div class="panel-card">
            <div class="panel-header">设备状态</div>
            <div class="panel-body">
              <div class="muted">暂无数据</div>
            </div>
          </div>
        </div>
        <!-- 右侧悬浮面板（告警队列 + 预警事件统计） -->
        <div class="right-panels">
          <div class="panel-card tall">
            <div class="panel-header">告警队列</div>
            <div class="panel-body" style="padding:8px;">
              <AlertPanel />
            </div>
          </div>
          <div class="panel-card">
            <div class="panel-header">预警事件统计</div>
            <div class="panel-body"><div class="muted">暂无数据</div></div>
          </div>
        </div>
        <!-- 小标签面板已移除 -->
      </a-layout-content>

      
    </a-layout>

    <!-- No bottom console on main screen -->
  </a-layout>
</template>

<style scoped>
.mapwrap { position:absolute; inset:0; }
.marker-camera { width:12px; height:12px; border-radius:50%; background: var(--accent-color); border:2px solid rgba(27,146,253,0.6); box-shadow:0 0 0 2px rgba(27,146,253,0.35); cursor:pointer; }
/* 左侧三个悬浮面板栈 */
.left-panels { position:absolute; left:12px; top:12px; width:300px; display:flex; flex-direction:column; gap:12px; }
.panel-card { background: var(--panel-bg); border:1px solid var(--panel-border); border-radius:8px; box-shadow:0 2px 10px rgba(0,0,0,0.25); overflow:hidden; backdrop-filter: blur(2px); }
.panel-header { font-weight:600; color: var(--accent-color); padding:8px 10px; border-bottom:1px solid rgba(27,146,253,0.25); }
.panel-body { padding:10px; color: var(--text-color); min-height:100px; }
.muted { color: var(--text-muted); }
/* 右侧三个悬浮面板栈 */
.right-panels { position:absolute; right:12px; top:12px; width:380px; display:flex; flex-direction:column; gap:12px; }
.panel-card.tall { min-height:260px; }
</style>
