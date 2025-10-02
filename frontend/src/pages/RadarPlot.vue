<script setup lang="ts">
import { computed } from 'vue'
import {
  radarLoading,
  radarError,
  radarInfo,
  radarDisplayedTargets,
  radarTableTargets,
  radarTargetTrails,
  radarStatus,
  radarTargetCount,
  radarTimestamp,
  radarElapsedMs,
  radarHostUsed,
  radarCtrlPortUsed,
  radarDataPortUsed,
  radarActualDataPort,
  radarPayloadLength,
  radarProtocol,
  radarMaxRangeValue,
  refreshRadarTargets
} from '@/store/radar'

const loading = radarLoading
const error = radarError
const info = radarInfo
const displayedTargets = radarDisplayedTargets
const tableTargets = radarTableTargets
const targetTrails = radarTargetTrails

const status = radarStatus
const targetCount = radarTargetCount
const timestamp = radarTimestamp
const elapsedMs = radarElapsedMs
const hostUsed = radarHostUsed
const ctrlPortUsed = radarCtrlPortUsed
const dataPortUsed = radarDataPortUsed
const actualDataPort = radarActualDataPort
const payloadLength = radarPayloadLength
const protocol = radarProtocol

function refresh() {
  refreshRadarTargets()
}

const radarWidth = 640
const radarHeight = 360
const centerX = radarWidth / 2
const centerY = radarHeight - 24
const maxRadarRadius = Math.min(centerX, centerY) - 16
const levelCount = 5

const maxRangeValue = radarMaxRangeValue

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
  return displayedTargets.value.map(t => {
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
  return Object.entries(targetTrails.value)
    .map(([id, trail]) => {
      if (!trail.length) return { id: Number(id), d: '' }
      const path = trail
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
const protocolDisplay = computed(() => protocol.value || 'UDP')

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
