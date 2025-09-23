<script setup lang="ts">
import { ref, computed, onMounted, watch, onUnmounted } from 'vue'
import { radarHost, radarCtrlPort, radarDataPort, radarUseTcp } from '@/store/config'
import { pushRadarAlarm } from '@/store/alerts'

interface RadarTarget {
  id: number
  longitudinalDistance: number
  lateralDistance: number
  speed: number
  range: number
  angle: number
  amplitude: number
  snr: number
  rcs: number
  elementCount: number
  targetLength: number
  detectionFrames: number
  trackState: number
  reserve1: number
  reserve2: number
}

interface RadarTargetsResponse {
  ok: boolean
  error?: string
  host?: string
  controlPort?: number
  dataPort?: number
  actualDataPort?: number
  tcp?: boolean
  timeoutMs?: number
  elapsedMs?: number
  status?: number
  targetCount?: number
  targets?: RadarTarget[]
  timestamp?: string
  payloadLength?: number
}

const loading = ref(false)
const error = ref<string | null>(null)
const info = ref<string | null>(null)

type TargetWithMeta = RadarTarget & { __lastSeen__?: number }
const displayedTargets = ref<TargetWithMeta[]>([])
const tableTargets = ref<TargetWithMeta[]>([])
type TrailPoint = { longitudinal: number; lateral: number; range: number; ts: number }
const targetTrails = ref<Record<number, TrailPoint[]>>({})

const status = ref<number | null>(null)
const targetCount = ref<number | null>(null)
const timestamp = ref<string | null>(null)
const elapsedMs = ref<number | null>(null)
const hostUsed = ref<string | null>(null)
const ctrlPortUsed = ref<number | null>(null)
const dataPortUsed = ref<number | null>(null)
const actualDataPort = ref<number | null>(null)
const payloadLength = ref<number | null>(null)
const protocol = ref<'TCP' | 'UDP' | null>(null)

const POLL_INTERVAL_MS = 3000
const TARGET_DISPLAY_TTL = POLL_INTERVAL_MS * 4
const MAX_TRAIL_LENGTH = 80
let pollingTimer: number | null = null
let refreshTimer: number | null = null

async function loadTargets() {
  const host = (radarHost.value || '').trim()
  if (!host) {
    error.value = '请先在系统设置中填写雷达 IP 地址'
    info.value = null
    scheduleNext()
    return
  }

  const ctrlPort = Number(radarCtrlPort.value) || 20000
  const dataPort = Number(radarDataPort.value) || ctrlPort
  const useTcp = !!radarUseTcp.value

  loading.value = true
  error.value = null
  hostUsed.value = host
  ctrlPortUsed.value = ctrlPort
  dataPortUsed.value = dataPort
  actualDataPort.value = null
  protocol.value = useTcp ? 'TCP' : 'UDP'

  try {
    const resp = await fetch('/api/radar/targets', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ host, port: ctrlPort, dataPort, useTcp })
    })
    if (!resp.ok) throw new Error(`请求失败 (${resp.status})`)

    const data: RadarTargetsResponse = await resp.json()
    timestamp.value = data.timestamp ?? null
    elapsedMs.value = data.elapsedMs ?? null
    hostUsed.value = data.host ?? host
    ctrlPortUsed.value = data.controlPort ?? ctrlPort
    dataPortUsed.value = data.dataPort ?? dataPort
    actualDataPort.value = data.actualDataPort ?? dataPortUsed.value
    protocol.value = data.tcp ? 'TCP' : 'UDP'
    payloadLength.value = data.payloadLength ?? null
    status.value = data.status ?? null
    targetCount.value = data.targetCount ?? null

    if (!data.ok) {
      error.value = data.error || '雷达数据不可用'
      return
    }

    const currentTargets = Array.isArray(data.targets) ? data.targets : []

    if (currentTargets.length) {
      const previousIds = new Set(tableTargets.value.map(t => t.id))
      displayedTargets.value = mergeTargets(displayedTargets.value, currentTargets)
      tableTargets.value = mergeTableTargets(tableTargets.value, currentTargets)
      updateTrails(currentTargets)
      const newTargets = currentTargets.filter(t => !previousIds.has(t.id))
      newTargets.forEach(t => {
        pushRadarAlarm({
          id: t.id,
          range: t.range,
          speed: t.speed,
          place: '相控阵雷达',
          angle: t.angle
        })
      })
      info.value = null
    } else if (!displayedTargets.value.length) {
      info.value = '未检测到目标。这可能表示当前雷达周围没有目标，或雷达尚未输出目标信息。'
    } else {
      info.value = '当前帧无新增目标，继续显示上一帧的目标位置。'
    }
  } catch (e: any) {
    error.value = e?.message || String(e)
  } finally {
    cleanupExpiredTargets()
    loading.value = false
    scheduleNext()
  }
}

function mergeTargets(existing: TargetWithMeta[], incoming: RadarTarget[]): TargetWithMeta[] {
  const now = Date.now()
  const map = new Map<number, TargetWithMeta>()
  existing.forEach(t => map.set(t.id, t))
  incoming.forEach(t => {
    const enriched: TargetWithMeta = { ...t, __lastSeen__: now }
    map.set(t.id, enriched)
  })
  return Array.from(map.values())
}

function mergeTableTargets(existing: TargetWithMeta[], incoming: RadarTarget[]): TargetWithMeta[] {
  const now = Date.now()
  const map = new Map<number, TargetWithMeta>()
  existing.forEach(t => map.set(t.id, t))
  incoming.forEach(t => {
    const enriched: TargetWithMeta = { ...t, __lastSeen__: now }
    map.set(t.id, enriched)
  })
  return Array.from(map.values()).sort((a, b) => (b.__lastSeen__ ?? 0) - (a.__lastSeen__ ?? 0))
}

function updateTrails(incoming: RadarTarget[]) {
  const now = Date.now()
  const next: Record<number, TrailPoint[]> = { ...targetTrails.value }
  incoming.forEach(t => {
    const arr = next[t.id] ? [...next[t.id]] : []
    arr.push({ longitudinal: t.longitudinalDistance, lateral: t.lateralDistance, range: t.range, ts: now })
    const trimmed = arr.filter(p => now - p.ts <= TARGET_DISPLAY_TTL)
    if (trimmed.length > MAX_TRAIL_LENGTH) trimmed.splice(0, trimmed.length - MAX_TRAIL_LENGTH)
    next[t.id] = trimmed
  })
  targetTrails.value = next
}

function cleanupExpiredTargets() {
  const now = Date.now()
  const ttl = TARGET_DISPLAY_TTL
  displayedTargets.value = displayedTargets.value.filter(t => {
    const lastSeen = t.__lastSeen__ ?? now
    return now - lastSeen <= ttl
  })

  const trails = { ...targetTrails.value }
  Object.entries(trails).forEach(([key, trail]) => {
    const filtered = trail.filter(p => now - p.ts <= ttl)
    if (filtered.length) {
      trails[Number(key)] = filtered
    } else {
      delete trails[Number(key)]
    }
  })
  targetTrails.value = trails
}

function scheduleNext() {
  if (pollingTimer) window.clearTimeout(pollingTimer)
  pollingTimer = window.setTimeout(() => { void loadTargets() }, POLL_INTERVAL_MS)
}

function refresh() {
  if (pollingTimer) window.clearTimeout(pollingTimer)
  void loadTargets()
}

onMounted(() => { void loadTargets() })

watch([radarHost, radarCtrlPort, radarDataPort, radarUseTcp], () => {
  if (refreshTimer) window.clearTimeout(refreshTimer)
  refreshTimer = window.setTimeout(() => { void loadTargets() }, 500)
})

onUnmounted(() => {
  if (pollingTimer) window.clearTimeout(pollingTimer)
  if (refreshTimer) window.clearTimeout(refreshTimer)
})

const radarWidth = 640
const radarHeight = 360
const centerX = radarWidth / 2
const centerY = radarHeight - 24
const maxRadarRadius = Math.min(centerX, centerY) - 16
const levelCount = 5

const maxRangeValue = computed(() => {
  const ranges = [
    ...displayedTargets.value.map(t => t.range),
    ...Object.values(targetTrails.value).flatMap(trail => trail.map(p => p.range))
  ]
  const fallback = 50
  const raw = ranges.length ? Math.max(...ranges, fallback) : fallback
  const step = Math.max(5, Math.ceil(raw / levelCount / 5) * 5)
  return step * levelCount
})

const arcLevels = computed(() => {
  const maxRange = maxRangeValue.value
  const step = maxRange / levelCount
  const arcs: { range: number; radius: number }[] = []
  for (let i = 1; i <= levelCount; i++) {
    const range = step * i
    arcs.push({ range, radius: (range / maxRange) * maxRadarRadius })
  }
  return arcs
})

function arcPath(radius: number) {
  const startX = centerX - radius
  const startY = centerY
  const endX = centerX + radius
  return `M ${startX} ${startY} A ${radius} ${radius} 0 0 1 ${endX} ${startY}`
}

const backgroundPath = computed(() => {
  const R = maxRadarRadius
  const startX = centerX - R
  const startY = centerY
  const endX = centerX + R
  return `M ${startX} ${startY} A ${R} ${R} 0 0 1 ${endX} ${startY} L ${centerX} ${centerY} Z`
})

const arcPaths = computed(() => arcLevels.value.map(level => ({
  d: arcPath(level.radius),
  range: level.range,
  leftLabel: { x: centerX - level.radius - 24, y: centerY - 4 },
  rightLabel: { x: centerX + level.radius + 12, y: centerY - 4 }
})))

const radialLines = computed(() => {
  const angles = [-90, -60, -30, 0, 30, 60, 90]
  return angles.map(angle => {
    const rad = (angle * Math.PI) / 180
    const x2 = centerX + maxRadarRadius * Math.sin(rad)
    const y2 = centerY - maxRadarRadius * Math.cos(rad)
    return {
      x1: centerX,
      y1: centerY,
      x2,
      y2,
      dashed: angle === 0
    }
  })
})

function projectToScreen(longitudinal: number, lateral: number, range: number, maxRange: number) {
  const limitedRange = Math.min(range, maxRange)
  const radius = (limitedRange / maxRange) * maxRadarRadius
  const angleRad = Math.atan2(lateral, longitudinal)
  return {
    x: centerX + radius * Math.sin(angleRad),
    y: centerY - radius * Math.cos(angleRad)
  }
}

const radarPoints = computed(() => {
  const maxRange = maxRangeValue.value || 1
  const now = Date.now()
  const liveTargets = displayedTargets.value.filter(t => now - (t as any).__lastSeen__ <= POLL_INTERVAL_MS * 4)
  return liveTargets.map(t => {
    const { x, y } = projectToScreen(t.longitudinalDistance, t.lateralDistance, t.range, maxRange)
    return {
      id: t.id,
      x,
      y,
      range: t.range,
      angle: t.angle,
      speed: t.speed,
      amplitude: t.amplitude
    }
  })
})

const radarTrails = computed(() => {
  const maxRange = maxRangeValue.value || 1
  const now = Date.now()
  return Object.entries(targetTrails.value)
    .map(([id, trail]) => {
      const valid = trail.filter(p => now - p.ts <= TARGET_DISPLAY_TTL)
      if (!valid.length) return { id: Number(id), d: '' }
      const path = valid
        .map((point, idx) => {
          const { x, y } = projectToScreen(point.longitudinal, point.lateral, point.range, maxRange)
          return `${idx === 0 ? 'M' : 'L'} ${x} ${y}`
        })
        .join(' ')
      return { id: Number(id), d: path }
    })
    .filter(item => item.d.length > 0)
})

const tableColumns = [
  { title: '目标', dataIndex: 'id', key: 'id', width: 80 },
  { title: '纵向距离 (m)', dataIndex: 'longitudinal', key: 'longitudinal', width: 140 },
  { title: '横向距离 (m)', dataIndex: 'lateral', key: 'lateral', width: 140 },
  { title: '径向距离 (m)', dataIndex: 'range', key: 'range', width: 140 },
  { title: '速度 (m/s)', dataIndex: 'speed', key: 'speed', width: 120 },
  { title: '幅度 (dB)', dataIndex: 'amplitude', key: 'amplitude', width: 120 },
  { title: '信噪比 (dB)', dataIndex: 'snr', key: 'snr', width: 120 },
  { title: 'RCS (㎡)', dataIndex: 'rcs', key: 'rcs', width: 120 },
  { title: 'CFAR 点数', dataIndex: 'elementCount', key: 'elementCount', width: 120 },
  { title: '目标长度 (m)', dataIndex: 'targetLength', key: 'targetLength', width: 120 },
  { title: '检测帧数', dataIndex: 'detectionFrames', key: 'detectionFrames', width: 120 },
  { title: '航迹状态', dataIndex: 'trackState', key: 'trackState', width: 120 }
]

const tableData = computed(() => tableTargets.value.map(t => ({
  key: t.id,
  id: `#${t.id}`,
  longitudinal: formatNumber(t.longitudinalDistance, 2),
  lateral: formatNumber(t.lateralDistance, 2),
  range: formatNumber(t.range, 2),
  speed: formatNumber(t.speed, 2),
  amplitude: t.amplitude,
  snr: t.snr,
  rcs: formatNumber(t.rcs, 2),
  elementCount: t.elementCount,
  targetLength: formatNumber(t.targetLength, 1),
  detectionFrames: t.detectionFrames,
  trackState: t.trackState
})))

const timestampDisplay = computed(() => formatTimestamp(timestamp.value))
const elapsedDisplay = computed(() => (elapsedMs.value == null ? '—' : `${elapsedMs.value} ms`))
const ctrlPortDisplay = computed(() => (ctrlPortUsed.value && ctrlPortUsed.value > 0 ? ctrlPortUsed.value : '—'))
const dataPortDisplay = computed(() => (dataPortUsed.value && dataPortUsed.value > 0 ? dataPortUsed.value : '—'))
const actualDataPortDisplay = computed(() => (actualDataPort.value && actualDataPort.value > 0 ? actualDataPort.value : null))
const protocolDisplay = computed(() => protocol.value || (radarUseTcp.value ? 'TCP' : 'UDP'))

function formatTimestamp(val: string | null) {
  if (!val) return '—'
  const d = new Date(val)
  return Number.isNaN(d.getTime()) ? val : d.toLocaleString()
}

function formatNumber(value: number, fraction = 1) {
  if (!Number.isFinite(value)) return '--'
  const factor = Math.pow(10, Math.max(0, fraction))
  return (Math.round(value * factor) / factor).toFixed(fraction)
}
</script>

<template>
  <a-layout style="min-height:calc(100vh - 64px); background:var(--bg-color); color:#000;">
    <a-layout-content style="padding:12px;">
      <a-card size="small" class="radar-card">
        <template #title>
          <span class="radar-title">相控阵雷达</span>
        </template>

        <div class="header-row">
          <div class="meta">
            <span>目标获取：{{ hostUsed || '—' }}:{{ ctrlPortDisplay }}</span>
            <span>协议：{{ protocolDisplay }}</span>
            <span v-if="dataPortDisplay !== '—'">监听端口：{{ dataPortDisplay }}</span>
            <span v-if="actualDataPortDisplay">数据端口：{{ actualDataPortDisplay }}</span>
            <span>状态字：{{ status ?? '—' }}</span>
            <span>目标数：{{ targetCount ?? '—' }}</span>
            <span>耗时：{{ elapsedDisplay }}</span>
            <span>帧长度：{{ payloadLength ?? '—' }}</span>
            <span>时间：{{ timestampDisplay }}</span>
          </div>
          <div class="actions">
            <a-spin size="small" v-if="loading" />
            <a-button size="small" type="primary" ghost @click="refresh">刷新</a-button>
          </div>
        </div>

        <a-alert v-if="error" type="error" :message="error" show-icon style="margin-bottom:12px;" />
        <a-alert v-else-if="info" type="info" :message="info" show-icon style="margin-bottom:12px;" />

        <div class="radar-stage">
          <svg :width="radarWidth" :height="radarHeight" :viewBox="`0 0 ${radarWidth} ${radarHeight}`">
            <defs>
              <radialGradient id="radarGradient" cx="0.5" cy="1" r="0.9">
                <stop offset="0%" stop-color="#1c3a63" stop-opacity="0.9" />
                <stop offset="100%" stop-color="#162d4a" stop-opacity="0.95" />
              </radialGradient>
            </defs>
            <path :d="backgroundPath" fill="url(#radarGradient)" stroke="#2b4770" stroke-width="1.5" />

            <g class="radar-arcs">
              <path
                v-for="arc in arcPaths"
                :key="arc.range"
                :d="arc.d"
                class="arc"
              />
              <g v-for="arc in arcPaths" :key="'label-' + arc.range">
                <text :x="arc.leftLabel.x" :y="arc.leftLabel.y" class="arc-label">{{ arc.range }}m</text>
                <text :x="arc.rightLabel.x" :y="arc.rightLabel.y" class="arc-label">{{ arc.range }}m</text>
              </g>
            </g>

            <g class="radial-lines">
              <line
                v-for="(line, idx) in radialLines"
                :key="idx"
                :x1="line.x1" :y1="line.y1" :x2="line.x2" :y2="line.y2"
                :class="['radial-line', { dashed: line.dashed }]"
              />
            </g>

            <circle :cx="centerX" :cy="centerY" r="8" class="radar-origin" />

            <g class="radar-trails">
              <path
                v-for="trail in radarTrails"
                :key="'trail-' + trail.id"
                :d="trail.d"
              />
            </g>

            <g class="radar-points">
              <g v-for="point in radarPoints" :key="point.id">
                <circle :cx="point.x" :cy="point.y" r="7" class="radar-point" />
                <text :x="point.x" :y="point.y - 14" class="point-label">#{{ point.id }}</text>
                <text :x="point.x" :y="point.y + 18" class="point-meta">
                  {{ formatNumber(point.range, 1) }}m / {{ formatNumber(point.angle, 1) }}°
                </text>
              </g>
            </g>
          </svg>
        </div>

        <div v-if="!radarPoints.length && !error" class="empty">暂无雷达目标数据</div>

        <a-table
          size="small"
          bordered
          :columns="tableColumns"
          :data-source="tableData"
          :pagination="false"
          style="margin-top:16px"
        />
      </a-card>
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
.radar-card {
  background: #141b2d;
  border-color: #1f2d46;
  color: #fff;
}
.radar-title {
  color: #fff;
}
.header-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  color: rgba(255, 255, 255, 0.75);
  gap: 12px;
} 
.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 13px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.radar-stage {
  background: #101828;
  border: 1px solid #1f2d46;
  border-radius: 12px;
  padding: 16px;
  display: flex;
  justify-content: center;
  align-items: center;
}
svg {
  overflow: visible;
}
.radar-arcs .arc {
  stroke: #33557d;
  stroke-width: 1;
  fill: none;
}
.arc-label {
  fill: rgba(255, 255, 255, 0.65);
  font-size: 12px;
}
.radial-line {
  stroke: #264266;
  stroke-width: 1;
}
.radial-line.dashed {
  stroke-dasharray: 6 6;
  stroke: #4a95ff;
  stroke-width: 1.2;
}
.radar-origin {
  fill: #4a95ff;
  stroke: #ffffff;
  stroke-width: 2;
}
.radar-point {
  fill: #ff4d4f;
  stroke: #ffffff;
  stroke-width: 2;
}
.radar-trails path {
  fill: none;
  stroke: rgba(255, 77, 79, 0.7);
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.point-label {
  fill: #ffd166;
  font-size: 12px;
  font-weight: 600;
  text-anchor: middle;
}
.point-meta {
  fill: rgba(255, 255, 255, 0.8);
  font-size: 11px;
  text-anchor: middle;
}
.empty {
  border: 1px dashed #3b4d6b;
  color: rgba(255, 255, 255, 0.7);
  padding: 24px;
  text-align: center;
  border-radius: 8px;
  background: rgba(22, 34, 52, 0.6);
  margin-top: 16px;
}

@media (max-width: 900px) {
  .header-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .meta {
    font-size: 12px;
    gap: 8px;
  }
  .radar-stage {
    padding: 12px;
  }
  svg {
    max-width: 100%;
    height: auto;
  }
}
</style>
