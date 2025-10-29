import { computed, readonly, ref, watch, WatchStopHandle } from 'vue'
import { radarHost, radarCtrlPort, radarDataPort, radarUseTcp } from './config'
import { pushRadarAlarm } from './alerts'

export interface RadarTarget {
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

export interface RadarTargetsResponse {
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

type TargetWithMeta = RadarTarget & { __lastSeen__?: number }
export type { TargetWithMeta }

type TrailPoint = { longitudinal: number; lateral: number; range: number; ts: number }
export type { TrailPoint }

const POLL_INTERVAL_MS = 1000
const TARGET_DISPLAY_TTL = POLL_INTERVAL_MS * 6
const MAX_TRAIL_LENGTH = 120

const loading = ref(false)
const error = ref<string | null>(null)
const info = ref<string | null>(null)

const displayedTargets = ref<TargetWithMeta[]>([])
const tableTargets = ref<TargetWithMeta[]>([])
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

const connected = ref(false)
let pollingTimer: number | null = null
let refreshTimer: number | null = null
let configStopHandle: WatchStopHandle | null = null

function scheduleNext(delay = POLL_INTERVAL_MS) {
  if (pollingTimer) window.clearTimeout(pollingTimer)
  pollingTimer = window.setTimeout(() => { void loadTargets() }, delay)
}

function scheduleRefresh(delay = 500) {
  if (refreshTimer) window.clearTimeout(refreshTimer)
  refreshTimer = window.setTimeout(() => { void loadTargets(true) }, delay)
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

async function loadTargets(forceImmediate = false) {
  if (!connected.value && !forceImmediate) return

  const host = (radarHost.value || '').trim()
  if (!host) {
    error.value = '请先在系统设置中填写雷达 IP 地址'
    info.value = null
    cleanupExpiredTargets()
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
      body: JSON.stringify({ host, port: ctrlPort, dataPort, useTcp, timeoutMs: 1200, maxFrames: 6 })
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

export function connectRadar() {
  if (connected.value) return
  connected.value = true
  void loadTargets(true)
  if (configStopHandle) configStopHandle()
  configStopHandle = watch(
    [radarHost, radarCtrlPort, radarDataPort, radarUseTcp],
    () => {
      displayedTargets.value = []
      tableTargets.value = []
      targetTrails.value = {}
      scheduleRefresh()
    }
  )
}

export function refreshRadarTargets() {
  scheduleRefresh(0)
}

export function disconnectRadar() {
  connected.value = false
  if (pollingTimer) window.clearTimeout(pollingTimer)
  if (refreshTimer) window.clearTimeout(refreshTimer)
  if (configStopHandle) configStopHandle()
  pollingTimer = null
  refreshTimer = null
  configStopHandle = null
}

export const radarLoading = readonly(loading)
export const radarError = readonly(error)
export const radarInfo = readonly(info)
export const radarDisplayedTargets = readonly(displayedTargets)
export const radarTableTargets = readonly(tableTargets)
export const radarTargetTrails = readonly(targetTrails)
export const radarStatus = readonly(status)
export const radarTargetCount = readonly(targetCount)
export const radarTimestamp = readonly(timestamp)
export const radarElapsedMs = readonly(elapsedMs)
export const radarHostUsed = readonly(hostUsed)
export const radarCtrlPortUsed = readonly(ctrlPortUsed)
export const radarDataPortUsed = readonly(dataPortUsed)
export const radarActualDataPort = readonly(actualDataPort)
export const radarPayloadLength = readonly(payloadLength)
export const radarProtocol = readonly(protocol)

const LEVEL_COUNT = 5

export const radarMaxRangeValue = computed(() => {
  const ranges = [
    ...displayedTargets.value.map(t => t.range),
    ...Object.values(targetTrails.value).flatMap(trail => trail.map(p => p.range))
  ]
  const fallback = 50
  const raw = ranges.length ? Math.max(...ranges, fallback) : fallback
  const step = Math.max(5, Math.ceil(raw / LEVEL_COUNT / 5) * 5)
  return step * LEVEL_COUNT
})
