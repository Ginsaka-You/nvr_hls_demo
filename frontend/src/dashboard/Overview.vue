<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import AlertPanel from '@/components/AlertPanel.vue'
import { loadAmap } from '@/lib/loadAmap'
import { radarDeviceState, cameraDeviceState } from '@/store/devices'

type Cam = { id: string, name: string, lat?: number, lng?: number }
type Alarm = { id: string, level: 'info'|'minor'|'major'|'critical', source: string, place: string, time: string, summary: string, deviceId?: string }

// Map start location (上坊孙吴墓)
const startedPin = [118.9146, 31.9626]
// Devices (map markers)
const cameras = ref<Cam[]>([
  { id: 'cam401', name: '入口摄像头', lat: 31.9627, lng: 118.9146 },
  { id: 'cam402', name: '停车场摄像头', lat: 31.9624, lng: 118.9152 },
  { id: 'cam403', name: '馆内摄像头', lat: 31.9620, lng: 118.9150 },
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

const radarState = radarDeviceState
const camState = cameraDeviceState

const radarStatusClass = computed(() => ({ ok: radarState.value.status === 'ok', error: radarState.value.status === 'error' }))
const radarStatusMessage = computed(() => radarState.value.message)
const camStatusClass = computed(() => ({ ok: camState.value.status === 'ok', error: camState.value.status === 'error' }))
const camStatusMessage = computed(() => camState.value.message)

async function initMap() {
  try {
    const AMap = await loadAmap()
    if (!mapEl.value) return
    const satellite = new AMap.TileLayer.Satellite()
    const roadNet = new AMap.TileLayer.RoadNet()
    map.value = new AMap.Map(mapEl.value, {
      center: startedPin,
      zoom: 18,
      viewMode: '3D',
      layers: [satellite, roadNet]
    })
    map.value.addControl(new AMap.Scale())
    map.value.addControl(new AMap.ToolBar())
    cameras.value.forEach(cam => {
      if (cam.lng && cam.lat) {
        const el = document.createElement('div')
        el.className = 'marker-camera'
        el.title = cam.name
        const label = document.createElement('div')
        label.className = 'marker-label'
        label.textContent = cam.name
        const wrapper = document.createElement('div')
        const icon = document.createElement('div')
        icon.className = 'marker-icon'
        icon.innerHTML = '<svg width="18" height="22" viewBox="0 0 18 22" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M9 0C4.02944 0 0 3.9625 0 8.84375C0 13.725 6.75 22 9 22C11.25 22 18 13.725 18 8.84375C18 3.9625 13.9706 0 9 0ZM9 12.375C7.15906 12.375 5.6875 10.9356 5.6875 9.125C5.6875 7.31438 7.15906 5.875 9 5.875C10.8409 5.875 12.3125 7.31438 12.3125 9.125C12.3125 10.9356 10.8409 12.375 9 12.375Z" fill="#FF4D4F"/></svg>'
        wrapper.appendChild(icon)
        wrapper.appendChild(label)
        wrapper.className = 'marker-wrapper'
        wrapper.addEventListener('click', () => { selectedCamId.value = cam.id })
        const marker = new AMap.Marker({ position: [cam.lng, cam.lat], anchor: 'bottom-center', content: wrapper, offset: new AMap.Pixel(-9, -18) })
        marker.setMap(map.value)
      }
    })
    mapLoading.value = false
  } catch (e: any) {
    mapError.value = e?.message || String(e)
    mapLoading.value = false
  }
}

onMounted(() => {
  if (cameras.value.length && !selectedCamId.value) selectedCamId.value = cameras.value[0].id
  void initMap()
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
            <div class="panel-body device-status">
              <div class="status-item" :class="camStatusClass">
                <span class="label">摄像头</span>
                <span class="value">{{ camStatusMessage }}</span>
              </div>
              <div class="status-item" :class="radarStatusClass">
                <span class="label">雷达</span>
                <span class="value">{{ radarStatusMessage }}</span>
              </div>
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
.marker-wrapper { position: relative; display:flex; flex-direction:column; align-items:center; gap:4px; pointer-events:auto; }
.marker-icon { width:18px; height:22px; display:flex; justify-content:center; align-items:center; }
.marker-label { background: rgba(0,0,0,0.65); color:#fff; padding:2px 6px; border-radius:4px; font-size:12px; white-space:nowrap; box-shadow:0 2px 6px rgba(0,0,0,0.25); }
/* 左侧三个悬浮面板栈 */
.left-panels { position:absolute; left:12px; top:12px; width:300px; display:flex; flex-direction:column; gap:12px; }
.panel-card { background: var(--panel-bg); border:1px solid var(--panel-border); border-radius:8px; box-shadow:0 2px 10px rgba(0,0,0,0.25); overflow:hidden; backdrop-filter: blur(2px); }
.panel-header { font-weight:600; color: var(--accent-color); padding:8px 10px; border-bottom:1px solid rgba(27,146,253,0.25); }
.panel-body { padding:10px; color: var(--text-color); min-height:100px; }
.device-status { display:flex; flex-direction:column; gap:8px; }
.status-item { display:flex; justify-content:space-between; padding:6px 8px; border:1px solid rgba(27,146,253,0.2); border-radius:6px; }
.status-item.ok { border-color: rgba(82, 196, 26, 0.45); color: #52c41a; }
.status-item.error { border-color: rgba(255,77,79,0.45); color: #ff4d4f; }
.status-item .label { font-weight:600; }
.status-item .value { font-size:14px; }
.muted { color: var(--text-muted); }
/* 右侧三个悬浮面板栈 */
.right-panels { position:absolute; right:12px; top:12px; width:380px; display:flex; flex-direction:column; gap:12px; }
.panel-card.tall { min-height:260px; }
</style>
