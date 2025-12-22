<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { Pie, Area } from '@antv/g2plot'
import AlertPanel from '@/components/AlertPanel.vue'
import { loadAmap } from '@/lib/loadAmap'
import { radarDeviceState, cameraDeviceState, imsiDeviceState } from '@/store/devices'
import { alarms } from '@/store/alerts'

type Cam = { id: string, name: string, lat?: number, lng?: number }
type Alarm = {
  id: string
  level: 'info'|'minor'|'major'|'critical'
  source: string
  place: string
  time: string
  summary: string
  deviceId?: string
  occurredAt?: number
  soundLightTriggered?: boolean
  status?: string
}

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
const disposalEl = ref<HTMLDivElement | null>(null)
const trendEl = ref<HTMLDivElement | null>(null)
const piePlot = ref<any | null>(null)
const areaPlot = ref<any | null>(null)

const rankingData = [
  { name: '北门入口-主摄', count: 158, percent: 90 },
  { name: '地下停车场-B区', count: 124, percent: 75 },
  { name: '围墙-东侧红外', count: 98, percent: 60 },
  { name: '主楼大厅-西侧', count: 76, percent: 45 },
  { name: '机房重地-走廊', count: 35, percent: 20 }
]

const disposalData = [
  { type: '已处置', value: 850 },
  { type: '处理中', value: 65 },
  { type: '待派单', value: 24 },
  { type: '误报归档', value: 132 }
]

const trendData = [
  { time: '00:00', value: 12 }, { time: '02:00', value: 5 },
  { time: '04:00', value: 8 }, { time: '06:00', value: 25 },
  { time: '08:00', value: 89 }, { time: '10:00', value: 145 },
  { time: '12:00', value: 110 }, { time: '14:00', value: 95 },
  { time: '16:00', value: 88 }, { time: '18:00', value: 162 },
  { time: '20:00', value: 205 }, { time: '22:00', value: 90 }
]

function onPickAlarm(a: Alarm) {
  if (a.deviceId) {
    selectedCamId.value = a.deviceId
  }
}

const radarState = radarDeviceState
const camState = cameraDeviceState
const imsiState = imsiDeviceState

const radarStatusClass = computed(() => ({ ok: radarState.value.status === 'ok', error: radarState.value.status === 'error' }))
const radarStatusMessage = computed(() => radarState.value.message)
const camStatusClass = computed(() => ({ ok: camState.value.status === 'ok', error: camState.value.status === 'error' }))
const camStatusMessage = computed(() => camState.value.message)
const imsiStatusClass = computed(() => ({ ok: imsiState.value.status === 'ok', error: imsiState.value.status === 'error' }))
const imsiStatusMessage = computed(() => imsiState.value.message)

const alarmPriority: Record<Alarm['level'], number> = {
  info: 0,
  minor: 1,
  major: 2,
  critical: 3
}

function getAlarmAction(a: Alarm) {
  const deviceId = a.deviceId || ''
  if (!deviceId.startsWith('risk:')) return null
  const actionId = deviceId.split(':')[1]?.toUpperCase()
  if (actionId === 'A2' || actionId === 'A3') return actionId
  return null
}

function isRiskAction(a: Alarm) {
  return getAlarmAction(a) !== null
}

const riskAlarms = computed(() => alarms.value.filter(isRiskAction))
const pendingRiskAlarms = computed(() => riskAlarms.value.filter((a) => !a.status || a.status === '未处理'))

function mergeRiskAlarms(items: Alarm[]) {
  const COOLDOWN_WINDOW_MS = 10 * 60 * 1000
  const sorted = [...items].sort((a, b) => (a.occurredAt ?? 0) - (b.occurredAt ?? 0))
  const merged: Alarm[] = []
  let current: Alarm[] | null = null
  let currentLastTime = 0

  for (const item of sorted) {
    const action = getAlarmAction(item)
    const timeMs = item.occurredAt ?? 0
    if (!action || timeMs <= 0) {
      merged.push(item)
      continue
    }
    const startNewByTrigger = action === 'A2' && item.soundLightTriggered === true
    const startNewByGap = current && timeMs - currentLastTime > COOLDOWN_WINDOW_MS
    if (!current || startNewByTrigger || startNewByGap) {
      current = [item]
      merged.push(item)
    } else {
      current.push(item)
      const primary = pickPrimaryAlarm(current)
      const existingIdx = merged.length - 1
      if (existingIdx >= 0) {
        merged[existingIdx] = primary
      }
    }
    currentLastTime = timeMs
  }

  return merged.sort((a, b) => (b.occurredAt ?? 0) - (a.occurredAt ?? 0))
}

function pickPrimaryAlarm(items: Alarm[]) {
  let picked = items[0]
  let pickedScore = alarmPriority[picked.level]
  let pickedAction = getAlarmAction(picked)
  let pickedActionScore = pickedAction === 'A3' ? 1 : 0
  let pickedTime = picked.occurredAt ?? 0
  for (let i = 1; i < items.length; i += 1) {
    const current = items[i]
    const score = alarmPriority[current.level]
    const currentTime = current.occurredAt ?? 0
    const action = getAlarmAction(current)
    const actionScore = action === 'A3' ? 1 : 0
    if (
      score > pickedScore
      || (score === pickedScore && actionScore > pickedActionScore)
      || (score === pickedScore && actionScore === pickedActionScore && currentTime > pickedTime)
    ) {
      picked = current
      pickedScore = score
      pickedAction = action
      pickedActionScore = actionScore
      pickedTime = currentTime
    }
  }
  return picked
}

const mergedRiskAlarms = computed(() => mergeRiskAlarms(riskAlarms.value))

const topAlarm = computed(() => {
  if (!mergedRiskAlarms.value.length) return null
  let picked = mergedRiskAlarms.value[0]
  let pickedScore = alarmPriority[picked.level]
  for (let i = 1; i < mergedRiskAlarms.value.length; i += 1) {
    const current = mergedRiskAlarms.value[i]
    const score = alarmPriority[current.level]
    if (score > pickedScore) {
      picked = current
      pickedScore = score
    }
  }
  return picked
})

const disposalRate = computed(() => {
  const total = disposalData.reduce((sum, item) => sum + item.value, 0)
  const resolved = disposalData
    .filter(item => item.type === '已处置' || item.type === '误报归档')
    .reduce((sum, item) => sum + item.value, 0)
  return total > 0 ? Math.round((resolved / total) * 100) : 0
})

function rankingTextColor(index: number) {
  return index < 3 ? '#00e5ff' : '#94a3b8'
}

function progressStrokeColor(index: number) {
  if (index === 0) {
    return { '0%': '#ff4d4d', '100%': '#f9cb28' }
  }
  if (index === 1) {
    return '#fa8c16'
  }
  if (index === 2) {
    return '#fa8c16'
  }
  return '#00e5ff'
}

function initPlots() {
  if (piePlot.value) {
    piePlot.value.destroy()
    piePlot.value = null
  }
  if (areaPlot.value) {
    areaPlot.value.destroy()
    areaPlot.value = null
  }

  if (disposalEl.value) {
    piePlot.value = new Pie(disposalEl.value, {
      data: disposalData,
      angleField: 'value',
      colorField: 'type',
      radius: 1,
      innerRadius: 0.64,
      autoFit: true,
      color: ['#00e5ff', '#facc15', '#ff4d4d', '#334155'],
      label: {
        type: 'spider',
        labelHeight: 28,
        content: (datum: any) => `${datum.type}\n${datum.value}`,
        style: { fill: '#94a3b8' }
      },
      statistic: {
        title: false,
        content: {
          style: {
            whiteSpace: 'pre-wrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            color: '#ffffff',
            fontSize: '20px',
            fontWeight: 600
          },
          content: `${disposalRate.value}%\n处置率`
        }
      },
      legend: false,
      theme: 'dark',
      interactions: [{ type: 'element-selected' }, { type: 'element-active' }]
    })
    piePlot.value.render()
  }

  if (trendEl.value) {
    areaPlot.value = new Area(trendEl.value, {
      data: trendData,
      xField: 'time',
      yField: 'value',
      smooth: true,
      autoFit: true,
      areaStyle: () => {
        return {
          fill: 'l(270) 0:#00E5FF 0.5:rgba(0, 229, 255, 0.3) 1:rgba(0, 229, 255, 0)'
        }
      },
      line: {
        color: '#00e5ff',
        size: 2
      },
      xAxis: {
        label: { style: { fill: '#94a3b8' } },
        line: { style: { stroke: '#334155' } }
      },
      yAxis: {
        label: { style: { fill: '#94a3b8' } },
        grid: { line: { style: { stroke: '#1e293b', lineDash: [4, 4] } } }
      },
      tooltip: {
        domStyles: {
          'g2-tooltip': {
            backgroundColor: 'rgba(4, 9, 18, 0.9)',
            border: '1px solid #00e5ff',
            color: '#ffffff'
          }
        }
      }
    })
    areaPlot.value.render()
  }
}

async function initMap() {
  try {
    const AMap = await loadAmap()
    if (!mapEl.value) return
    map.value = new AMap.Map(mapEl.value, {
      center: startedPin,
      zoom: 18,
      viewMode: '3D',
      mapStyle: 'amap://styles/f304981b319af3b49afffbbb9bf4d06f'
    })
    map.value.addControl(new AMap.Scale())
    map.value.addControl(new AMap.ToolBar())
    cameras.value.forEach(cam => {
      if (cam.lng && cam.lat) {
        const dot = document.createElement('div')
        dot.className = 'marker-dot'
        const marker = new AMap.Marker({
          position: [cam.lng, cam.lat],
          anchor: 'center',
          content: dot,
          offset: new AMap.Pixel(0, 0)
        })
        marker.setLabel({
          offset: new AMap.Pixel(0, -26),
          content: `<div class="tech-label">${cam.name}</div>`,
          direction: 'top'
        })
        marker.on('click', () => { selectedCamId.value = cam.id })
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
  initPlots()
})

onBeforeUnmount(() => {
  try { map.value && map.value.destroy && map.value.destroy() } catch (_) {}
  if (piePlot.value) {
    piePlot.value.destroy()
    piePlot.value = null
  }
  if (areaPlot.value) {
    areaPlot.value.destroy()
    areaPlot.value = null
  }
})

// keep only map logic here; alerts are handled globally in App via store

  
</script>

<template>
  <a-layout style="height: calc(100vh - 64px); background: var(--bg-color); color: #000000;">
    <a-layout>
      <!-- Center Map -->
      <a-layout-content style="position:relative;">
        <div class="map-stage">
          <div ref="mapEl" class="mapwrap" />
          <div class="map-mask"></div>
          <div class="screen-overlay"></div>
          <div class="scan-line"></div>
        </div>
        <!-- 左侧三个悬浮信息框 -->
        <div class="left-panels">
          <div class="panel-card">
            <div class="panel-header">点位报警排名</div>
            <div class="panel-body ranking-body">
              <a-list :data-source="rankingData" :split="false" class="ranking-list">
                <template #renderItem="{ item, index }">
                  <a-list-item :key="item.name" class="ranking-item">
                    <div class="ranking-row">
                      <div class="ranking-title" :style="{ color: rankingTextColor(index) }">
                        <span class="ranking-index">{{ String(index + 1).padStart(2, '0') }}</span>
                        <span class="ranking-name">{{ item.name }}</span>
                      </div>
                      <span class="ranking-count">{{ item.count }}次</span>
                    </div>
                    <a-progress
                      :percent="item.percent"
                      :show-info="false"
                      :stroke-color="progressStrokeColor(index)"
                      trail-color="rgba(255, 255, 255, 0.1)"
                      :stroke-width="4"
                    />
                  </a-list-item>
                </template>
              </a-list>
            </div>
          </div>
          <div class="panel-card">
            <div class="panel-header">人员处置次数统计</div>
            <div class="panel-body chart-body">
              <div ref="disposalEl" class="plot-box plot-donut"></div>
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
                <div class="status-item" :class="imsiStatusClass">
                  <span class="label">手机围栏</span>
                  <span class="value">{{ imsiStatusMessage }}</span>
                </div>
              </div>
            </div>
        </div>
        <!-- 右侧悬浮面板（A:高危弹窗 / B:待处理任务 / C:数据态势） -->
        <div class="right-panels">
          <div class="panel-card alert-hero" :class="{ active: topAlarm }">
            <div class="panel-header">实时高危弹窗</div>
            <div class="panel-body alert-hero-body">
              <div v-if="topAlarm" class="alert-hero-content">
                <div class="alert-hero-title">{{ topAlarm.summary }}</div>
                <div class="alert-hero-meta">{{ topAlarm.place }} · {{ topAlarm.source }} · {{ topAlarm.time }}</div>
              </div>
              <div v-else class="muted">暂无高危报警</div>
            </div>
          </div>
          <div class="panel-card queue-card">
            <div class="panel-header">待处理任务列表</div>
            <div class="panel-body queue-body">
              <AlertPanel :items="pendingRiskAlarms" />
            </div>
          </div>
          <div class="panel-card stats-card">
            <div class="panel-header">数据态势</div>
            <div class="panel-body chart-body">
              <div ref="trendEl" class="plot-box plot-trend"></div>
            </div>
          </div>
        </div>
        <!-- 小标签面板已移除 -->
      </a-layout-content>

      
    </a-layout>

    <!-- No bottom console on main screen -->
  </a-layout>
</template>

<style scoped>
.map-stage {
  position: absolute;
  inset: 0;
  isolation: isolate;
  z-index: 0;
}

.mapwrap {
  position: absolute;
  inset: 0;
  z-index: 0;
  filter: contrast(1.2) brightness(1.1);
}

.map-mask {
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  background: rgba(4, 9, 18, 0.3);
  mix-blend-mode: multiply;
}

.screen-overlay {
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(0, 255, 255, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0, 255, 255, 0.03) 1px, transparent 1px);
  background-size: 40px 40px;
}

.scan-line {
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  background: linear-gradient(
    to bottom,
    transparent 50%,
    rgba(0, 229, 255, 0.08) 51%,
    transparent 52%
  );
  background-size: 100% 4px;
  opacity: 0.35;
}

:deep(.marker-dot) {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #00e5ff;
  border: 2px solid rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 10px #00e5ff, 0 0 20px #00e5ff;
  animation: pulse 2s infinite;
  position: relative;
}

:deep(.marker-dot)::before,
:deep(.marker-dot)::after {
  content: "";
  position: absolute;
  left: 50%;
  top: 50%;
  background: rgba(0, 229, 255, 0.8);
  transform: translate(-50%, -50%);
}

:deep(.marker-dot)::before {
  width: 2px;
  height: 14px;
}

:deep(.marker-dot)::after {
  width: 14px;
  height: 2px;
}

:deep(.marker-dot.alarm) {
  background: #ff2d2d;
  box-shadow: 0 0 16px rgba(255, 45, 45, 0.7);
  animation: pulse-red 1s infinite;
}

:deep(.amap-marker-label) {
  background-color: transparent !important;
  border: none !important;
  box-shadow: none !important;
  padding: 0 !important;
}

:deep(.tech-label) {
  color: #00e5ff;
  font-family: "Roboto Mono", monospace;
  font-size: 12px;
  font-weight: 600;
  text-shadow: 0 0 6px rgba(0, 229, 255, 0.8);
  background: rgba(0, 0, 0, 0.5);
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid rgba(0, 229, 255, 0.3);
  white-space: nowrap;
}
/* 左侧三个悬浮面板栈 */
.left-panels { position:absolute; left:12px; top:12px; width:300px; display:flex; flex-direction:column; gap:12px; z-index: 3; }
.panel-card {
  background: rgba(4, 9, 18, 0.8);
  border: 1px solid #00e5ff;
  border-radius: 8px;
  box-shadow:
    0 0 15px rgba(0, 229, 255, 0.4),
    inset 0 0 20px rgba(0, 229, 255, 0.1),
    0 2px 10px rgba(0, 0, 0, 0.25);
  overflow: hidden;
  position: relative;
}
.panel-card::before {
  content: "";
  position: absolute;
  inset: -1px;
  background:
    linear-gradient(to right, #00e5ff 2px, transparent 2px) 0 0,
    linear-gradient(to bottom, #00e5ff 2px, transparent 2px) 0 0,
    linear-gradient(to left, #00e5ff 2px, transparent 2px) 100% 0,
    linear-gradient(to bottom, #00e5ff 2px, transparent 2px) 100% 0,
    linear-gradient(to left, #00e5ff 2px, transparent 2px) 100% 100%,
    linear-gradient(to top, #00e5ff 2px, transparent 2px) 100% 100%,
    linear-gradient(to right, #00e5ff 2px, transparent 2px) 0 100%,
    linear-gradient(to top, #00e5ff 2px, transparent 2px) 0 100%;
  background-size: 10px 10px;
  background-repeat: no-repeat;
  pointer-events: none;
  opacity: 0.85;
}
.panel-header { font-weight:600; color: #7ee7ff; padding:8px 10px; border-bottom:1px solid rgba(0, 229, 255, 0.2); letter-spacing: 0.5px; }
.panel-body { padding:10px; color: var(--text-color); min-height:100px; }
.panel-body.chart-body {
  min-height: 160px;
}
.panel-body.ranking-body {
  min-height: 160px;
}
.plot-box {
  width: 100%;
  height: 170px;
}
.plot-donut {
  height: 180px;
}
.plot-trend {
  height: 180px;
}
.ranking-list :deep(.ant-list-item) {
  padding: 6px 0;
  border: none;
}
.ranking-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ranking-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.ranking-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
}
.ranking-index {
  font-family: "Roboto Mono", monospace;
  font-size: 12px;
  opacity: 0.85;
}
.ranking-name {
  color: inherit;
}
.ranking-count {
  color: #e2f6ff;
  font-family: "Roboto Mono", monospace;
  font-size: 13px;
}
.device-status { display:flex; flex-direction:column; gap:8px; }
.status-item { display:flex; justify-content:space-between; padding:6px 8px; border:1px solid rgba(27,146,253,0.2); border-radius:6px; }
.status-item.ok { border-color: rgba(82, 196, 26, 0.45); color: #52c41a; }
.status-item.error { border-color: rgba(255,77,79,0.45); color: #ff4d4f; }
.status-item .label { font-weight:600; }
.status-item .value { font-size:14px; }
.muted { color: #94a3b8; }
/* 右侧三个悬浮面板栈 */
.right-panels {
  position:absolute;
  right:12px;
  top:12px;
  bottom:12px;
  width:380px;
  display:grid;
  grid-template-rows: 2fr 5fr 3fr;
  gap:12px;
  z-index: 3;
}
.right-panels .panel-card {
  display:flex;
  flex-direction:column;
  min-height:0;
}
.right-panels .panel-body {
  flex:1;
  min-height:0;
}
.alert-hero { transition: box-shadow 0.2s ease, border-color 0.2s ease; }
.alert-hero.active {
  border-left: 4px solid #ff4d4f;
  border-top: 1px solid rgba(255, 77, 79, 0.5);
  border-right: 1px solid rgba(255, 77, 79, 0.5);
  border-bottom: 1px solid rgba(255, 77, 79, 0.5);
  background: linear-gradient(90deg, rgba(255, 77, 79, 0.2), rgba(255, 77, 79, 0));
  box-shadow: 0 6px 22px rgba(255,77,79,0.25);
}
.alert-hero.active .panel-header {
  color: var(--danger-color);
  border-bottom-color: rgba(255,77,79,0.4);
}
.alert-hero-body {
  display:flex;
  flex-direction:column;
  justify-content:center;
  gap:8px;
}
.alert-hero-title {
  font-size:20px;
  font-weight:700;
  color: var(--text-color);
}
.alert-hero-meta {
  font-size:13px;
  color: var(--text-muted);
}

@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(0, 229, 255, 0.7); }
  70% { box-shadow: 0 0 0 12px rgba(0, 229, 255, 0); }
  100% { box-shadow: 0 0 0 0 rgba(0, 229, 255, 0); }
}

@keyframes pulse-red {
  0% { box-shadow: 0 0 0 0 rgba(255, 45, 45, 0.7); }
  70% { box-shadow: 0 0 0 10px rgba(255, 45, 45, 0); }
  100% { box-shadow: 0 0 0 0 rgba(255, 45, 45, 0); }
}
.queue-body {
  padding:8px 6px 8px 8px;
  max-height:100%;
  overflow-y:auto;
}
</style>
