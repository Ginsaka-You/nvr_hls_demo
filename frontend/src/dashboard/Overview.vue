<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { Liquid, Area } from '@antv/g2plot'
import { CameraOutlined, RadarChartOutlined, MobileOutlined } from '@ant-design/icons-vue'
import AlertPanel from '@/components/AlertPanel.vue'
import { loadAmap } from '@/lib/loadAmap'
import { radarDeviceState, cameraDeviceState, imsiDeviceState } from '@/store/devices'
import { cameraHealth } from '@/store/cameraHealth'
import { alarms } from '@/store/alerts'

type Cam = { id: string, name: string, lat?: number, lng?: number }
type Alarm = {
  id: string
  eventId?: string
  level: 'info'|'minor'|'major'|'critical'
  source: string
  place: string
  time: string
  summary: string
  deviceId?: string
  occurredAt?: number
  soundLightTriggered?: boolean
  status?: string
  snapshotUrl?: string
  snapshots?: string[]
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
const liquidPlot = ref<any | null>(null)
const areaPlot = ref<any | null>(null)
const previewVisible = ref(false)
const previewImages = ref<string[]>([])
const previewIndex = ref(0)
const prefetchedImages = new Set<string>()

const rankingData = [
  { name: '北门入口-主摄', count: 158, percent: 90 },
  { name: '地下停车场-B区', count: 124, percent: 75 },
  { name: '围墙-东侧红外', count: 98, percent: 60 },
  { name: '主楼大厅-西侧', count: 76, percent: 45 },
  { name: '机房重地-走廊', count: 35, percent: 20 }
]

const disposalData = [
  { type: '已处置', value: 850 },
  { type: '未处置', value: 89 }
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

const radarStatusClass = computed(() => ({
  ok: radarState.value.status === 'ok',
  error: radarState.value.status === 'error',
  warn: radarState.value.status === 'unknown'
}))
const radarStatusMessage = computed(() => radarState.value.message)
const camStatusClass = computed(() => ({
  ok: camState.value.status === 'ok',
  error: camState.value.status === 'error',
  warn: camState.value.status === 'unknown'
}))
const camStatusMessage = computed(() => camState.value.message)
const camStatusText = computed(() => {
  const total = cameraHealth.total.value
  const available = cameraHealth.available.value
  if (total > 0) {
    return `${available}/${total} Online`
  }
  return camStatusMessage.value
})
const imsiStatusClass = computed(() => ({
  ok: imsiState.value.status === 'ok',
  error: imsiState.value.status === 'error',
  warn: imsiState.value.status === 'unknown'
}))
const imsiStatusMessage = computed(() => imsiState.value.message)

const alarmPriority: Record<Alarm['level'], number> = {
  info: 0,
  minor: 1,
  major: 2,
  critical: 3
}

const pendingRiskDb = ref<Alarm[]>([])
let pendingRefreshTimer: number | null = null
const currentPreviewImage = computed(() => previewImages.value[previewIndex.value] || null)

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

function actionPriority(action: string | null) {
  if (action === 'A3') return 2
  if (action === 'A2') return 1
  return 0
}

function mergeRiskAlarms(items: Alarm[]) {
  const COOLDOWN_WINDOW_MS = 10 * 60 * 1000
  const sorted = [...items].sort((a, b) => (a.occurredAt ?? 0) - (b.occurredAt ?? 0))
  const groups: Alarm[][] = []
  const merged: Alarm[] = []
  let currentGroup: Alarm[] | null = null
  let currentLastTime = 0

  for (const item of sorted) {
    const action = getAlarmAction(item)
    const timeMs = item.occurredAt ?? 0
    if (!action || timeMs <= 0) {
      groups.push([item])
      currentGroup = null
      currentLastTime = 0
      continue
    }
    const startNewByTrigger = action === 'A2' && item.soundLightTriggered === true
    const startNewByGap = currentGroup && timeMs - currentLastTime > COOLDOWN_WINDOW_MS
    if (!currentGroup || startNewByTrigger || startNewByGap) {
      currentGroup = [item]
      groups.push(currentGroup)
    } else {
      currentGroup.push(item)
    }
    currentLastTime = timeMs
  }

  for (const group of groups) {
    if (group.length === 1) {
      merged.push(group[0])
      continue
    }
    merged.push(buildMergedRiskAlarm(group))
  }

  return merged.sort((a, b) => (b.occurredAt ?? 0) - (a.occurredAt ?? 0))
}

function buildMergedRiskAlarm(items: Alarm[]) {
  const actions = Array.from(new Set(items.map(getAlarmAction).filter(Boolean))) as string[]
  actions.sort((a, b) => actionPriority(a) - actionPriority(b))
  const primary = pickPrimaryRiskAlarm(items)
  const latest = pickLatestAlarm(items)
  const snapshotMap = new Map<string, string>()
  for (const item of items) {
    const list = item.snapshots && item.snapshots.length ? item.snapshots : (item.snapshotUrl ? [item.snapshotUrl] : [])
    list.filter(Boolean).forEach((url) => {
      const key = normalizeSnapshotKey(url)
      if (!key) return
      if (!snapshotMap.has(key)) {
        snapshotMap.set(key, url)
      }
    })
  }
  const snapshots = Array.from(snapshotMap.values())
  const snapshotUrl = snapshots[0] ?? primary.snapshotUrl
  return {
    ...primary,
    snapshotUrl,
    snapshots,
    time: latest.time,
    occurredAt: latest.occurredAt,
    status: primary.status,
    summary: primary.summary
  }
}

function pickPrimaryRiskAlarm(items: Alarm[]) {
  return items.reduce((best, current) => {
    const bestAction = actionPriority(getAlarmAction(best))
    const currentAction = actionPriority(getAlarmAction(current))
    if (currentAction > bestAction) {
      return current
    }
    if (currentAction < bestAction) {
      return best
    }
    const bestTs = best.occurredAt ?? 0
    const currentTs = current.occurredAt ?? 0
    return currentTs > bestTs ? current : best
  }, items[0])
}

function pickLatestAlarm(items: Alarm[]) {
  return items.reduce((latest, current) => {
    const latestTs = latest.occurredAt ?? 0
    const currentTs = current.occurredAt ?? 0
    return currentTs > latestTs ? current : latest
  }, items[0])
}

function normalizeRiskActionId(raw: unknown): string | null {
  if (typeof raw !== 'string') return null
  const match = raw.trim().toUpperCase().match(/A[1-3]/)
  return match ? match[0] : null
}

function safeDecodeUrlSegment(value: string): string {
  try {
    return decodeURIComponent(value.replace(/\+/g, '%20'))
  } catch {
    return value
  }
}

function normalizeSnapshotKey(url: string | null | undefined): string | null {
  if (!url) return null
  let trimmed = url.trim()
  if (!trimmed) return null
  const originMatch = trimmed.match(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/]+(\/.*)?$/)
  if (originMatch) {
    trimmed = originMatch[1] || '/'
  }
  let cut = trimmed.length
  const queryIdx = trimmed.indexOf('?')
  const hashIdx = trimmed.indexOf('#')
  if (queryIdx >= 0) cut = Math.min(cut, queryIdx)
  if (hashIdx >= 0) cut = Math.min(cut, hashIdx)
  if (cut !== trimmed.length) {
    trimmed = trimmed.slice(0, cut)
  }
  trimmed = trimmed.replace(/\\/g, '/')
  const parts = trimmed.split('/')
  const decodedParts = parts
    .map((part) => safeDecodeUrlSegment(part))
    .filter((part) => part.length > 0)
  if (!decodedParts.length) return null
  let normalized = decodedParts.join('/')
  const markerIdx = normalized.indexOf('snapshots/')
  if (markerIdx >= 0) {
    normalized = normalized.slice(markerIdx + 'snapshots/'.length)
  }
  normalized = normalized.replace(/^\/+/, '')
  return normalized || null
}

function dedupeSnapshots(urls: string[]): string[] {
  const map = new Map<string, string>()
  for (const url of urls) {
    const key = normalizeSnapshotKey(url)
    if (!key) continue
    if (!map.has(key)) {
      map.set(key, url)
    }
  }
  return Array.from(map.values())
}

function normalizeRiskLevel(level: unknown, classification?: unknown): Alarm['level'] {
  if (typeof level === 'string' && level) {
    const mapped = level.toLowerCase()
    if (mapped === 'critical' || mapped === 'major' || mapped === 'minor' || mapped === 'info') {
      return mapped
    }
  }
  if (typeof classification === 'string' && classification) {
    const upper = classification.toUpperCase()
    if (upper === 'P1') return 'critical'
    if (upper === 'P2') return 'major'
    if (upper === 'P3') return 'minor'
  }
  return 'major'
}

function parseTimestamp(value: unknown): number | null {
  if (value == null) return null
  if (value instanceof Date) {
    const ts = value.getTime()
    return Number.isFinite(ts) ? ts : null
  }
  const text = String(value).trim()
  if (!text) return null
  const ts = Date.parse(text)
  return Number.isFinite(ts) ? ts : null
}

function formatAlarmTime(value: unknown): string {
  const ts = parseTimestamp(value)
  if (ts == null) return new Date().toLocaleTimeString()
  return new Date(ts).toLocaleTimeString()
}

function resolveSnapshotUrl(payload: any): string | undefined {
  if (!payload) return undefined
  const direct = [payload.snapshotUrl, payload.snapshot_url]
  for (const item of direct) {
    if (typeof item === 'string' && item.trim().length > 0) {
      return item.trim()
    }
  }
  if (Array.isArray(payload.snapshots)) {
    const first = payload.snapshots.find((value: unknown) => typeof value === 'string' && value.trim().length > 0)
    if (first) return first.trim()
  }
  return undefined
}

function resolveSnapshots(payload: any): string[] {
  if (!payload) return []
  const list: string[] = []
  if (Array.isArray(payload.snapshots)) {
    payload.snapshots.forEach((value: unknown) => {
      if (typeof value === 'string' && value.trim().length > 0) {
        list.push(value.trim())
      }
    })
  }
  const direct = [payload.snapshotUrl, payload.snapshot_url]
  for (const item of direct) {
    if (typeof item === 'string' && item.trim().length > 0) {
      list.push(item.trim())
    }
  }
  return dedupeSnapshots(list)
}

function resolveRiskPlace(payload: any): string {
  if (payload?.camChannel && String(payload.camChannel).trim().length > 0) return String(payload.camChannel).trim()
  if (payload?.cam_channel && String(payload.cam_channel).trim().length > 0) return String(payload.cam_channel).trim()
  if (Array.isArray(payload?.channels) && payload.channels.length) {
    const channels = payload.channels
      .map((value: unknown) => (typeof value === 'string' ? value.trim() : ''))
      .filter((value: string) => value.length > 0)
    if (channels.length) return channels.join(',')
  }
  if (Array.isArray(payload?.details?.channels) && payload.details.channels.length) {
    const channels = payload.details.channels
      .map((value: unknown) => (typeof value === 'string' ? value.trim() : ''))
      .filter((value: string) => value.length > 0)
    if (channels.length) return channels.join(',')
  }
  return '未知'
}

function mapRiskActionItemToAlarm(item: any): Alarm | null {
  const actionId = normalizeRiskActionId(item?.action || item?.actionId || item?.action_id)
  if (!actionId || (actionId !== 'A2' && actionId !== 'A3')) {
    return null
  }
  const classification = typeof item?.classification === 'string' ? item.classification : ''
  const scoreValue = typeof item?.score === 'number' && Number.isFinite(item.score)
    ? item.score
    : (typeof item?.score === 'string' ? Number(item.score) : NaN)
  const scoreText = Number.isFinite(scoreValue) ? `综合得分 ${scoreValue.toFixed(1)}` : ''
  const summaryText = typeof item?.summary === 'string' && item.summary.trim().length > 0
    ? item.summary.trim()
    : (actionId === 'A3' ? '风控模型升级至 A3' : '风控模型触发远程警报')
  const detailParts = [summaryText, scoreText, classification ? `优先级 ${classification}` : ''].filter(Boolean)
  const decidedAt = item?.decidedAt ?? item?.eventTime ?? item?.createdAt
  const occurredAt = parseTimestamp(decidedAt) ?? Date.now()
  const snapshots = resolveSnapshots(item)
  const eventId = typeof item?.eventId === 'string' && item.eventId
    ? item.eventId
    : (typeof item?.event_id === 'string' && item.event_id ? item.event_id : undefined)
  const place = resolveRiskPlace(item)
  return {
    id: String(item?.id ?? eventId ?? `risk-${Date.now().toString(36)}`),
    eventId,
    level: normalizeRiskLevel(item?.level, classification),
    source: place,
    place,
    time: formatAlarmTime(decidedAt),
    summary: detailParts.join(' ｜ '),
    deviceId: `risk:${actionId}`,
    occurredAt,
    soundLightTriggered: typeof item?.soundLightTriggered === 'boolean'
      ? item.soundLightTriggered
      : (typeof item?.sound_light_triggered === 'boolean' ? item.sound_light_triggered : undefined),
    status: typeof item?.status === 'string' && item.status ? item.status : '未处理',
    snapshotUrl: snapshots[0] || resolveSnapshotUrl(item),
    snapshots
  }
}

async function loadPendingRiskActions() {
  try {
    const params = new URLSearchParams({ limit: '500', actions: 'A2,A3' })
    const resp = await fetch(`/api/events/risk-actions?${params.toString()}`)
    if (!resp.ok) {
      pendingRiskDb.value = []
      return
    }
    const data = await resp.json()
    if (!Array.isArray(data)) {
      pendingRiskDb.value = []
      return
    }
    const mapped = data
      .map(mapRiskActionItemToAlarm)
      .filter((item): item is Alarm => Boolean(item))
    pendingRiskDb.value = mapped
    syncAlarmSnapshots(mapped)
  } catch {
    pendingRiskDb.value = []
  }
}

function getAlarmSnapshots(alarm: Alarm): string[] {
  if (alarm.snapshots && alarm.snapshots.length) return alarm.snapshots
  return alarm.snapshotUrl ? [alarm.snapshotUrl] : []
}

function syncAlarmSnapshots(items: Alarm[]) {
  if (!items.length) return
  const byEventId = new Map<string, Alarm>()
  const byId = new Map<string, Alarm>()
  for (const item of items) {
    if (item.eventId) byEventId.set(item.eventId, item)
    if (item.id) byId.set(item.id, item)
  }
  const updated = alarms.value.map((alarm) => {
    if (!isRiskAction(alarm)) return alarm
    if (alarm.snapshots && alarm.snapshots.length) return alarm
    const match = (alarm.eventId && byEventId.get(alarm.eventId)) || byId.get(alarm.id)
    if (!match || (!(match.snapshots && match.snapshots.length) && !match.snapshotUrl)) return alarm
    return {
      ...alarm,
      snapshotUrl: match.snapshotUrl ?? alarm.snapshotUrl,
      snapshots: match.snapshots ?? alarm.snapshots
    }
  })
  alarms.value = updated
}

function openPreviewGroup(images: string[], index = 0) {
  if (!images.length) return
  previewImages.value = images
  previewIndex.value = index
  previewVisible.value = true
  prefetchPreviewImages(images, index)
}

function nextPreview() {
  if (!previewImages.value.length) return
  const nextIndex = (previewIndex.value + 1) % previewImages.value.length
  previewIndex.value = nextIndex
}

function prevPreview() {
  if (!previewImages.value.length) return
  const prevIndex = (previewIndex.value - 1 + previewImages.value.length) % previewImages.value.length
  previewIndex.value = prevIndex
}

function formatPreviewNumber(value: number) {
  return String(value).padStart(2, '0')
}

function prefetchImage(url: string) {
  if (!url || prefetchedImages.has(url)) return
  prefetchedImages.add(url)
  const img = new Image()
  img.decoding = 'async'
  img.src = url
}

function prefetchPreviewImages(images: string[], index: number) {
  if (!images.length) return
  const total = images.length
  if (total <= 12) {
    images.forEach(prefetchImage)
    return
  }
  const current = images[index] || images[0]
  const next = images[(index + 1) % total]
  const prev = images[(index - 1 + total) % total]
  if (current) prefetchImage(current)
  if (next) prefetchImage(next)
  if (prev) prefetchImage(prev)
}

const mergedRiskAlarms = computed(() => mergeRiskAlarms(riskAlarms.value))
const pendingMergedRiskAlarms = computed(() =>
  mergeRiskAlarms(pendingRiskDb.value).filter((a) => !a.status || a.status === '未处理')
)

watch([previewVisible, previewImages], ([visible, images]) => {
  if (visible) {
    prefetchPreviewImages(images, previewIndex.value)
  }
})

watch(previewIndex, (value) => {
  if (previewVisible.value) {
    prefetchPreviewImages(previewImages.value, value)
  }
})

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

const topAlarmImages = computed(() => (topAlarm.value ? getAlarmSnapshots(topAlarm.value) : []))

const disposalRate = computed(() => {
  const total = disposalData.reduce((sum, item) => sum + item.value, 0)
  const resolved = disposalData
    .filter(item => item.type === '已处置' || item.type === '误报归档')
    .reduce((sum, item) => sum + item.value, 0)
  return total > 0 ? Math.round((resolved / total) * 100) : 0
})

const disposalBlocks = computed(() => disposalData.map(item => ({ name: item.type, value: item.value })))

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

function alarmLevelTitle(level: Alarm['level']) {
  if (level === 'critical') return '一级高危警报 (P1)'
  if (level === 'major') return '二级风险提示 (P2)'
  if (level === 'minor') return '三级关注提示 (P3)'
  return '信息提示 (P4)'
}

function alarmActionLabel(alarm: Alarm) {
  const action = getAlarmAction(alarm)
  if (action === 'A2') return 'A2需远程查看'
  if (action === 'A3') return 'A3需人员到现场查看'
  return alarmLevelTitle(alarm.level)
}

function alarmScoreText(summary: string) {
  const match = summary.match(/综合得分\s*([0-9.]+)/)
  return match ? match[1] : '--'
}

function alarmPriorityLabel(level: Alarm['level']) {
  if (level === 'critical') return 'P1 最高优先级'
  if (level === 'major') return 'P2 高风险'
  if (level === 'minor') return 'P3 中风险'
  return 'P4 提示'
}

function alarmStatusTags(alarm: Alarm) {
  const tags: string[] = []
  const priority = alarmPriorityLabel(alarm.level)
  if (priority) tags.push(priority)
  const parts = alarm.summary.split('｜').map(part => part.trim()).filter(Boolean)
  for (const part of parts) {
    if (part.includes('综合得分') || part.includes('优先级')) continue
    if (tags.includes(part)) continue
    tags.push(part)
    if (tags.length >= 3) break
  }
  return tags
}

function alarmStatusSummary(summary: string) {
  if (!summary) return ''
  const parts = summary.split('｜').map(part => part.trim()).filter(Boolean)
  const filtered = parts.filter(part => !part.includes('综合得分') && !part.includes('优先级'))
  return filtered.slice(0, 2).join('，')
}

function alarmSourceText(alarm: Alarm) {
  return alarm.place || '--'
}

function initPlots() {
  if (liquidPlot.value) {
    liquidPlot.value.destroy()
    liquidPlot.value = null
  }
  if (areaPlot.value) {
    areaPlot.value.destroy()
    areaPlot.value = null
  }

  if (disposalEl.value) {
    liquidPlot.value = new Liquid(disposalEl.value, {
      percent: Math.min(1, Math.max(0, disposalRate.value / 100)),
      radius: 0.9,
      autoFit: true,
      color: '#00e5ff',
      shape: 'circle',
      wave: { length: 128 },
      outline: {
        border: 1,
        distance: 1,
        style: {
          stroke: '#00e5ff',
          strokeOpacity: 0.4
        }
      },
      statistic: {
        title: false,
        content: {
          style: {
            whiteSpace: 'pre-wrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            color: '#ffffff',
            fontSize: '24px',
            fontWeight: 600
          },
          formatter: () => `${disposalRate.value}%`
        }
      },
      theme: {
        background: 'transparent'
      },
      interactions: [{ type: 'element-selected' }, { type: 'element-active' }]
    })
    liquidPlot.value.render()
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
  void loadPendingRiskActions()
  if (pendingRefreshTimer) {
    window.clearInterval(pendingRefreshTimer)
  }
  pendingRefreshTimer = window.setInterval(() => {
    void loadPendingRiskActions()
  }, 6000)
})

onBeforeUnmount(() => {
  try { map.value && map.value.destroy && map.value.destroy() } catch (_) {}
  if (liquidPlot.value) {
    liquidPlot.value.destroy()
    liquidPlot.value = null
  }
  if (areaPlot.value) {
    areaPlot.value.destroy()
    areaPlot.value = null
  }
  if (pendingRefreshTimer) {
    window.clearInterval(pendingRefreshTimer)
    pendingRefreshTimer = null
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
          <div class="panel-card disposal-card">
            <div class="panel-header">处置率</div>
            <div class="panel-body chart-body">
              <div class="disposal-liquid">
                <div class="liquid-gauge-container">
                  <div ref="disposalEl" class="plot-box plot-liquid"></div>
                </div>
                <div class="disposal-metrics">
                  <div v-for="item in disposalBlocks" :key="item.name" class="disposal-metric">
                    <div class="disposal-metric-name">{{ item.name }}</div>
                    <div class="disposal-metric-value">{{ item.value }}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div class="panel-card">
            <div class="panel-header">设备状态</div>
              <div class="panel-body device-status">
                <div class="status-item" :class="camStatusClass">
                  <div class="status-left">
                    <CameraOutlined class="status-icon" />
                    <span class="label">摄像头</span>
                  </div>
                  <div class="status-right">
                    <span class="status-dot"></span>
                    <span class="value">{{ camStatusText }}</span>
                  </div>
                </div>
                <div class="status-item" :class="radarStatusClass">
                  <div class="status-left">
                    <RadarChartOutlined class="status-icon" />
                    <span class="label">雷达</span>
                  </div>
                  <div class="status-right">
                    <span class="status-dot"></span>
                    <span class="value">{{ radarStatusMessage }}</span>
                  </div>
                </div>
                <div class="status-item" :class="imsiStatusClass">
                  <div class="status-left">
                    <MobileOutlined class="status-icon" />
                    <span class="label">手机围栏</span>
                  </div>
                  <div class="status-right">
                    <span class="status-dot"></span>
                    <span class="value">{{ imsiStatusMessage }}</span>
                  </div>
                </div>
              </div>
            </div>
        </div>
        <!-- 右侧悬浮面板（A:高危弹窗 / B:待处理任务 / C:数据态势） -->
        <div class="right-panels">
          <div class="panel-card alert-hero" :class="{ active: topAlarm }">
            <div class="panel-header alert-hero-header">
              <span class="alert-hero-label">实时高危报警</span>
              <span class="alert-hero-time">{{ topAlarm ? topAlarm.time : '--' }}</span>
            </div>
            <div class="panel-body alert-hero-body">
              <div v-if="topAlarm" class="alert-hero-content">
                <div class="alert-hero-media">
                  <button v-if="topAlarmImages.length" class="alert-hero-thumb-btn" type="button" @click="openPreviewGroup(topAlarmImages, 0)">
                    <span class="alert-hero-image">
                      <img :src="topAlarmImages[0]" alt="snapshot" />
                      <span v-if="topAlarmImages.length > 1" class="thumb-count">+{{ topAlarmImages.length - 1 }}</span>
                    </span>
                  </button>
                  <div v-else class="alert-hero-placeholder">暂无抓拍</div>
                </div>
                <div class="alert-hero-info">
                  <div class="alert-hero-title">{{ alarmActionLabel(topAlarm) }}</div>
                  <div class="alert-hero-meta">设备: {{ topAlarm.place }} | 触发源: {{ topAlarm.source }}</div>
                  <div class="alert-hero-tags">
                    <span class="hero-tag danger">风险分 {{ alarmScoreText(topAlarm.summary) }}</span>
                    <span class="hero-tag neutral">触发源: {{ alarmSourceText(topAlarm) }}</span>
                  </div>
                  <div class="alert-hero-actions">
                    <a-button size="small" class="hero-action ghost" @click="onPickAlarm(topAlarm)">查看详情</a-button>
                    <a-button size="small" class="hero-action danger" @click="onPickAlarm(topAlarm)">立即处置</a-button>
                  </div>
                </div>
              </div>
              <div v-else class="muted">暂无高危报警</div>
            </div>
          </div>
          <div class="panel-card queue-card">
            <div class="panel-header">待处理任务列表</div>
            <div class="panel-body queue-body">
              <AlertPanel :items="pendingMergedRiskAlarms" @preview="openPreviewGroup" />
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

  <a-modal
    v-model:visible="previewVisible"
    :footer="null"
    width="60vw"
    centered
    destroy-on-close
    wrapClassName="image-preview-modal"
    class="image-preview-modal-inner"
    :closable="false"
    :style="{ background: 'transparent', boxShadow: 'none' }"
    :maskStyle="{ backgroundColor: 'rgba(0, 15, 25, 0.9)', backdropFilter: 'blur(15px)', WebkitBackdropFilter: 'blur(15px)' }"
    :bodyStyle="{ background: 'transparent', padding: '0' }"
    @cancel="previewVisible = false"
  >
    <div class="preview-body" v-if="currentPreviewImage">
      <button class="preview-nav left" type="button" @click="prevPreview" v-if="previewImages.length > 1">‹</button>
      <div class="preview-frame">
        <img
          :src="currentPreviewImage"
          alt="snapshot preview"
          loading="eager"
          decoding="async"
          fetchpriority="high"
        />
      </div>
      <button class="preview-nav right" type="button" @click="nextPreview" v-if="previewImages.length > 1">›</button>
      <div class="preview-counter" v-if="previewImages.length > 1">
        {{ formatPreviewNumber(previewIndex + 1) }} / {{ formatPreviewNumber(previewImages.length) }}
      </div>
    </div>
  </a-modal>
  <teleport to="body">
    <button v-if="previewVisible" class="preview-close" type="button" @click="previewVisible = false">✕</button>
  </teleport>
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
.left-panels { position:absolute; left:12px; top:12px; bottom:12px; width:300px; display:flex; flex-direction:column; gap:12px; z-index: 3; }
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
  display: flex;
  flex-direction: column;
}
.panel-header { font-weight:600; color: #7ee7ff; padding:8px 10px; border-bottom:1px solid rgba(0, 229, 255, 0.2); letter-spacing: 0.5px; }
.panel-body { padding:10px; color: var(--text-color); min-height:100px; }
.panel-body.chart-body {
  min-height: 160px;
}
.panel-body.ranking-body {
  min-height: 160px;
}
.disposal-card { flex: 1 1 auto; }
.disposal-card .panel-body { flex: 1; display: flex; }
.disposal-liquid {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.disposal-card .plot-box {
  width: 100%;
  height: 100%;
}
.liquid-gauge-container {
  width: 160px;
  height: 160px;
  border-radius: 50%;
  box-shadow:
    inset 0 0 15px rgba(0, 229, 255, 0.3),
    0 0 20px rgba(0, 229, 255, 0.2);
  border: 0.5px solid rgba(0, 229, 255, 0.35);
  padding: 5px;
  background-color: rgba(0, 20, 30, 0.5);
  box-sizing: border-box;
  margin-top: 8px;
  margin-bottom: 8px;
}
.disposal-metrics {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.disposal-metric {
  border: 1px solid rgba(0, 229, 255, 0.2);
  background: rgba(15, 23, 42, 0.6);
  border-radius: 6px;
  padding: 6px 4px;
  text-align: center;
}
.disposal-metric-name {
  font-size: 11px;
  color: #94a3b8;
}
.disposal-metric-value {
  font-size: 14px;
  font-weight: 600;
  color: #e2f6ff;
  font-family: "Roboto Mono", monospace;
}
.plot-box {
  width: 100%;
  height: 170px;
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
.device-status { display:flex; flex-direction:column; gap:10px; }
.status-item {
  display:flex;
  justify-content:space-between;
  align-items:center;
  padding:8px 10px;
  border:1px solid rgba(0, 229, 255, 0.2);
  border-radius:10px;
  background: rgba(0, 229, 255, 0.06);
}
.status-left { display:flex; align-items:center; gap:8px; color:#e2f6ff; font-weight:600; }
.status-icon { font-size:16px; color:#7ee7ff; }
.status-right { display:flex; align-items:center; gap:8px; font-family:"Roboto Mono", monospace; font-size:12px; color:#94a3b8; }
.status-dot {
  width:8px;
  height:8px;
  border-radius:50%;
  background:#94a3b8;
  box-shadow: 0 0 6px rgba(148, 163, 184, 0.6);
}
.status-item.ok { border-color: rgba(82, 196, 26, 0.45); }
.status-item.ok .status-dot {
  background: #52c41a;
  box-shadow: 0 0 10px rgba(82, 196, 26, 0.8);
  animation: pulse-green 2.4s infinite;
}
.status-item.error { border-color: rgba(255,77,79,0.45); }
.status-item.error .status-dot {
  background: #ff4d4f;
  box-shadow: 0 0 12px rgba(255, 77, 79, 0.85);
  animation: pulse-red-fast 1.2s infinite;
}
.status-item.warn { border-color: rgba(250, 204, 21, 0.45); }
.status-item.warn .status-dot {
  background: #facc15;
  box-shadow: 0 0 10px rgba(250, 204, 21, 0.8);
}
.status-item .label { font-weight:600; }
.status-item .value { font-size:12px; }
.muted { color: #94a3b8; }
/* 右侧三个悬浮面板栈 */
.right-panels {
  position:absolute;
  right:12px;
  top:12px;
  bottom:12px;
  width:380px;
  display:grid;
  grid-template-rows: 2.1fr 4.9fr 3fr;
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
  gap:10px;
}
.alert-hero-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.alert-hero-label {
  font-size: inherit;
  color: #ff4d4f;
  font-weight: 600;
}
.alert-hero-time {
  margin-left: auto;
  font-size: 12px;
  color: #94a3b8;
  font-family: "Roboto Mono", monospace;
}
.alert-hero-content { display:flex; gap:12px; }
.alert-hero-media { width:40%; min-width:120px; }
.alert-hero-image {
  position: relative;
  display: block;
  width: 100%;
  height: 96px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid rgba(255, 77, 79, 0.7);
  background: rgba(15, 23, 42, 0.8);
  box-sizing: border-box;
}
.alert-hero-thumb-btn {
  padding: 0;
  cursor: pointer;
  background: none;
  border: none;
  display: block;
}
.alert-hero-thumb-btn img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.thumb-count {
  position: absolute;
  right: 6px;
  bottom: 6px;
  background: rgba(0, 0, 0, 0.4);
  color: #e2f6ff;
  font-size: 11px;
  padding: 0 4px;
  border-radius: 6px;
}
.alert-hero-image img { width:100%; height:100%; object-fit:contain; display:block; }
.alert-hero-placeholder {
  height: 96px;
  border-radius: 8px;
  border: 1px dashed rgba(71, 85, 105, 0.5);
  color: #94a3b8;
  display:flex;
  align-items:center;
  justify-content:center;
  font-size: 12px;
  background: rgba(15, 23, 42, 0.6);
}
.alert-hero-info { flex:1; display:flex; flex-direction:column; gap:8px; min-width:0; }
.alert-hero-title {
  font-size:16px;
  font-weight:600;
  color: #e2f6ff;
  letter-spacing: 0.2px;
}
.alert-hero-meta {
  font-size:12px;
  color: #94a3b8;
  line-height: 1.5;
}
.alert-hero-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.hero-tag {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
  border: 1px solid transparent;
  line-height: 1.4;
  white-space: nowrap;
}
.hero-tag.danger {
  background: rgba(255, 77, 79, 0.2);
  color: #ffffff;
  border-color: rgba(255, 77, 79, 0.55);
  font-weight: 600;
}
.hero-tag.neutral {
  background: rgba(15, 23, 42, 0.75);
  color: #cbd5f5;
  border-color: rgba(148, 163, 184, 0.35);
}
.alert-hero-actions { display:flex; justify-content:flex-start; gap:8px; }
.alert-hero-actions { margin-top: auto; }
.hero-action {
  border-radius: 6px;
  padding: 0 12px;
  height: 28px;
  font-size: 12px;
}
.hero-action.ghost {
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(100, 116, 139, 0.6);
  color: #e2f6ff;
}
.hero-action.danger {
  background: #ff4d4f;
  border-color: #ff4d4f;
  color: #ffffff;
  box-shadow: 0 0 12px rgba(255, 77, 79, 0.5);
  animation: action-pulse 1.6s infinite;
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

@keyframes pulse-green {
  0% { box-shadow: 0 0 0 0 rgba(82, 196, 26, 0.7); }
  70% { box-shadow: 0 0 0 8px rgba(82, 196, 26, 0); }
  100% { box-shadow: 0 0 0 0 rgba(82, 196, 26, 0); }
}

@keyframes pulse-red-fast {
  0% { box-shadow: 0 0 0 0 rgba(255, 77, 79, 0.8); }
  70% { box-shadow: 0 0 0 8px rgba(255, 77, 79, 0); }
  100% { box-shadow: 0 0 0 0 rgba(255, 77, 79, 0); }
}

@keyframes action-pulse {
  0% { box-shadow: 0 0 0 0 rgba(255, 77, 79, 0.6); }
  70% { box-shadow: 0 0 0 10px rgba(255, 77, 79, 0); }
  100% { box-shadow: 0 0 0 0 rgba(255, 77, 79, 0); }
}
.queue-body {
  padding:8px 6px 8px 8px;
  max-height:100%;
  overflow-y:auto;
}
@media (max-width: 1200px) {
  .alert-hero-content { flex-direction: column; }
  .alert-hero-media { width: 100%; }
}
.preview-body {
  position: relative;
  width: 100%;
  text-align: center;
  padding: 24px 0 40px;
}
.preview-frame {
  display: inline-block;
  padding: 4px;
  border: 2px solid #ff3333;
  border-radius: 8px;
  box-shadow:
    0 0 25px rgba(255, 50, 50, 0.6),
    0 0 45px rgba(255, 50, 50, 0.25);
}
.preview-body img {
  max-width: 100%;
  border-radius: 6px;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.35);
}
.preview-nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  border: none;
  background: transparent;
  color: #00e5ff;
  width: 52px;
  height: 80px;
  font-size: 32px;
  cursor: pointer;
  transition: all 0.3s ease;
  text-shadow: 0 0 10px #00e5ff;
  opacity: 0.7;
}
.preview-nav:hover {
  opacity: 1;
  text-shadow: 0 0 20px #00e5ff;
  transform: translateY(-50%) scale(1.1);
}
.preview-nav.left { left: 8px; }
.preview-nav.right { right: 8px; }
.preview-counter {
  position: absolute;
  bottom: -30px;
  left: 50%;
  transform: translateX(-50%);
  color: #ffffff;
  font-family: "Roboto Mono", monospace;
  font-size: 12px;
  letter-spacing: 1px;
  text-shadow: 0 0 8px rgba(255, 255, 255, 0.5);
}
.preview-close {
  position: fixed;
  top: 24px;
  right: 28px;
  border: none;
  background: transparent;
  color: rgba(255, 77, 79, 0.8);
  font-size: 22px;
  cursor: pointer;
  transition: color 0.2s ease, transform 0.2s ease;
  text-shadow: 0 0 12px rgba(255, 77, 79, 0.6);
  z-index: 2000;
}
.preview-close:hover {
  color: #ff4d4f;
  transform: rotate(12deg);
}
:global(.image-preview-modal .ant-modal-content) {
  background: transparent !important;
  box-shadow: none !important;
}
:global(.image-preview-modal .ant-modal-body) {
  padding: 0 !important;
  background: transparent !important;
}
:global(.image-preview-modal .ant-modal) {
  background: transparent !important;
}
:global(.image-preview-modal) {
  background: transparent !important;
}
:global(.image-preview-modal .ant-modal-wrap) {
  background: transparent !important;
}
:global(.image-preview-modal-inner .ant-modal-content),
:global(.image-preview-modal-inner .ant-modal-body),
:global(.image-preview-modal-inner .ant-modal-header),
:global(.image-preview-modal-inner .ant-modal-footer) {
  background: transparent !important;
  box-shadow: none !important;
  border: none !important;
}
:global(.image-preview-modal .ant-modal-header),
:global(.image-preview-modal .ant-modal-footer) {
  background: transparent !important;
  border: none !important;
}
</style>
