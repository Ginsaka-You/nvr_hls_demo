import { ref, Ref, watch } from 'vue'
import { nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort } from './config'

export type Alarm = {
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

export const alarms: Ref<Alarm[]> = ref([])

type RiskActionPayload = {
  eventId?: string
  event_id?: string
  action?: string
  actionId?: string
  action_id?: string
  classification?: string
  level?: string
  status?: string
  snapshotUrl?: string
  snapshot_url?: string
  snapshots?: string[]
  camChannel?: string
  cam_channel?: string
  eventTime?: string
  decidedAt?: string
  createdAt?: string
  summary?: string
  score?: number
}

function deriveCamChannel(channelId?: number, port?: number): string | undefined {
  if (typeof channelId === 'number' && channelId > 0) {
    const base = channelId
    let physical = base
    let stream = 1
    if (base > 32) {
      physical = ((base - 1) % 32) + 1
      stream = ((base - 1) / 32) + 1
    }
    return `${physical}${stream.toString().padStart(2, '0')}`
  }
  if (typeof port === 'number' && port > 0) {
    return `${port}01`
  }
  return undefined
}

type SoundLightAction = 'activate' | 'deactivate'
const SOUND_LIGHT_AUTO_OFF_MS = 8000
let soundLightOffTimer: number | null = null
export const soundLightMuted: Ref<boolean> = ref(false)

function pushAlarm(a: Alarm, options: { triggerSoundLight?: boolean } = {}) {
  const existed = alarms.value.some(item => item.id === a.id)
  alarms.value = [a, ...alarms.value.filter(item => item.id !== a.id)].slice(0, 200)
  if (!existed && options.triggerSoundLight && !soundLightMuted.value) {
    void triggerSoundLightAlarm('activate')
    scheduleSoundLightAutoOff()
  }
}

let esPush: EventSource | null = null
let connected = false

export function connectAlerts() {
  if (connected) return
  connected = true
  openStreams()
  void loadRiskActions()
  // Reconnect on config changes
  watch([nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort], () => {
    closeStreams()
    openStreams()
    void loadRiskActions()
  })
}

function openStreams() {
  try { if (esPush) { esPush.close(); esPush = null } } catch {}

  // Push (device -> server)
  try {
    esPush = new EventSource(`/api/nvr/alerts/subscribe`)
    esPush.onmessage = (ev) => handleEvent(ev)
    esPush.onerror = () => { try { esPush && esPush.close() } catch {}; esPush = null; setTimeout(openStreams, 3000) }
  } catch {}
}

function closeStreams() {
  try { esPush && esPush.close() } catch {}
  esPush = null
}

function handleEvent(ev: MessageEvent) {
  try {
    const data = JSON.parse((ev as any).data)
    if (data && data.type === 'alert') {
      pushAlarmFromEvent(data)
    } else if (data && data.type === 'risk') {
      pushRiskAlarm(data)
    }
  } catch {}
}

function mapEventType(et: string) {
  const s = et.toLowerCase()
  if (s.includes('field') || s.includes('intrusion')) return '区域入侵告警'
  if (s.includes('linedetection') || s.includes('tripwire')) return '越界侦测告警'
  if ((s.includes('region') && (s.includes('entrance') || s.includes('enter'))) || s.includes('areaenter')) {
    return '进入区域侦测告警'
  }
  if ((s.includes('region') && (s.includes('exit') || s.includes('leave') || s.includes('depart'))) || s.includes('areaexit')) {
    return '离开区域侦测告警'
  }
  if (s.includes('loiter') || s.includes('linger') || s.includes('stay')) {
    return '徘徊侦测告警'
  }
  if (s.includes('vmd') || s.includes('motion')) return '移动侦测告警'
  return et
}

function toNumber(value: any): number | undefined {
  if (value === null || value === undefined) return undefined
  const text = String(value).trim()
  if (!text) return undefined
  const num = Number(text)
  return Number.isFinite(num) ? num : undefined
}

function normalizeRiskActionId(raw: unknown): string | null {
  if (typeof raw !== 'string') return null
  const match = raw.trim().toUpperCase().match(/A[1-3]/)
  return match ? match[0] : null
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

function buildRiskEventId(actionId: string | null, decidedAt: unknown): string | null {
  const normalized = normalizeRiskActionId(actionId)
  const ts = parseTimestamp(decidedAt)
  if (!normalized || ts == null) return null
  return `risk-${normalized.toLowerCase()}-${ts}`
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
  return Array.from(new Set(list))
}

export function pushAlarmFromEvent(ev: any) {
  const camChannelRaw = typeof ev?.camChannel === 'string' ? ev.camChannel.trim() : undefined
  const channelRaw = toNumber(ev?.channelID)
  const portRaw = toNumber(ev?.port)
  const channelId = channelRaw !== undefined ? Math.trunc(channelRaw) : undefined
  const port = portRaw !== undefined ? Math.trunc(portRaw) : undefined
  const camChannel = camChannelRaw && camChannelRaw.length > 0
    ? camChannelRaw
    : deriveCamChannel(channelId, port)
  const camId = camChannel ? `cam${camChannel}` : undefined
  const et: string = (ev?.eventType || '').toString()
  const summary = et ? mapEventType(et) : '事件告警'
  const snapshots = resolveSnapshots(ev)
  const snapshotUrl = snapshots[0] || resolveSnapshotUrl(ev)
  const a: Alarm = {
    id: ev?.id || Math.random().toString(36).slice(2),
    level: (ev?.level || 'major') as any,
    source: '摄像头',
    place: camChannel ? camChannel : '摄像头',
    time: new Date().toLocaleTimeString(),
    summary,
    deviceId: camId,
    snapshotUrl,
    snapshots
  }
  pushAlarm(a)
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

function sanitizeChannels(channels: unknown): string[] {
  if (!Array.isArray(channels)) return []
  return channels
    .map((item) => (typeof item === 'string' ? item.trim() : ''))
    .filter((text) => text.length > 0)
}

function resolveRiskPlace(channels: string[], fallback?: unknown): string {
  if (channels.length) return channels.join(',')
  if (typeof fallback === 'string' && fallback.trim().length > 0) return fallback.trim()
  return '未知'
}

function resolveRiskChannels(payload: any): string[] {
  const direct = sanitizeChannels(payload?.channels)
  if (direct.length) return direct
  const detailsChannels = sanitizeChannels(payload?.details?.channels)
  if (detailsChannels.length) return detailsChannels
  const signalsChannels = sanitizeChannels(payload?.details?.signals?.channels)
  if (signalsChannels.length) return signalsChannels
  return []
}

function resolveRiskDisplayAction(actionId: string, payload: any, summaryText: string): string {
  if (payload?.upgrade === true) return 'A3'
  if (summaryText.includes('A3')) return 'A3'
  return actionId
}

function pushRiskAlarm(data: any) {
  const actionIdRaw = typeof data?.actionId === 'string' && data.actionId ? data.actionId : (typeof data?.action === 'string' ? data.action : 'A2')
  const actionId = normalizeRiskActionId(actionIdRaw) || 'A2'
  const eventId = data?.eventId || data?.event_id
  const resolvedEventId = typeof eventId === 'string' && eventId ? eventId : buildRiskEventId(actionId, data?.decidedAt)
  const rawId = data?.id
  const resolvedId = rawId != null
    ? String(rawId)
    : (resolvedEventId || buildRiskEventId(actionId, data?.decidedAt))
  const id = resolvedId || `risk-${Date.now().toString(36)}`
  const classification = typeof data?.classification === 'string' && data.classification ? data.classification : ''
  const scoreValue = typeof data?.score === 'number' && Number.isFinite(data.score)
    ? data.score
    : (typeof data?.score === 'string' ? Number(data.score) : NaN)
  const scoreText = Number.isFinite(scoreValue) ? `综合得分 ${scoreValue.toFixed(1)}` : ''
  const upgrade = data?.upgrade === true
  const nightMode = data?.nightMode === true
  const cooldownSecondsRaw = typeof data?.audioCooldownSeconds === 'number' ? data.audioCooldownSeconds : Number(data?.audioCooldownSeconds)
  const cooldownSeconds = Number.isFinite(cooldownSecondsRaw) ? Math.max(0, cooldownSecondsRaw) : null
  const shouldTriggerSoundLight = actionId === 'A2' && data?.triggerAudio !== false
  const summaryText = typeof data?.summary === 'string' && data.summary.trim().length > 0
    ? data.summary.trim()
    : '风控模型触发远程警报'
  const displayActionId = resolveRiskDisplayAction(actionId, data, summaryText)
  const rationale = typeof data?.rationale === 'string' && data.rationale.trim().length > 0
    ? data.rationale.trim()
    : ''
  const cooldownText = actionId === 'A2' && !shouldTriggerSoundLight && cooldownSeconds && cooldownSeconds > 0
    ? `声光报警冷却中（约 ${Math.ceil(cooldownSeconds)} 秒）`
    : ''
  const upgradeText = upgrade ? '已升级至 A3' : ''
  const nightLabel = nightMode ? '夜间模式' : ''
  const detailParts = [summaryText, rationale, scoreText, classification ? `优先级 ${classification}` : '', upgradeText, nightLabel, cooldownText]
    .filter(Boolean)
  const channels = resolveRiskChannels(data)
  const place = resolveRiskPlace(channels, data?.camChannel ?? data?.cam_channel)
  const decidedAt = typeof data?.decidedAt === 'string' && data.decidedAt
    ? new Date(data.decidedAt)
    : null
  const occurredAt = decidedAt && !Number.isNaN(decidedAt.getTime()) ? decidedAt.getTime() : Date.now()
  const time = decidedAt && !Number.isNaN(decidedAt.getTime())
    ? decidedAt.toLocaleTimeString()
    : new Date().toLocaleTimeString()
  const snapshots = resolveSnapshots(data)
  const snapshotUrl = snapshots[0] || resolveSnapshotUrl(data)
  const alarm: Alarm = {
    id,
    eventId: typeof resolvedEventId === 'string' && resolvedEventId ? resolvedEventId : undefined,
    level: normalizeRiskLevel(data?.level, classification),
    source: place,
    place,
    time,
    summary: detailParts.join(' ｜ '),
    deviceId: `risk:${displayActionId}`,
    occurredAt,
    soundLightTriggered: shouldTriggerSoundLight,
    status: typeof data?.status === 'string' && data.status ? data.status : '未处理',
    snapshotUrl,
    snapshots
  }
  pushAlarm(alarm, { triggerSoundLight: shouldTriggerSoundLight })
}

function mapRiskActionToAlarm(item: RiskActionPayload): Alarm | null {
  const actionId = normalizeRiskActionId(item.action || item.actionId || item.action_id)
  if (!actionId || (actionId !== 'A2' && actionId !== 'A3')) {
    return null
  }
  const eventId = item.eventId || item.event_id
  const decidedAt = item.decidedAt || item.eventTime || item.createdAt
  const occurredAt = parseTimestamp(decidedAt) ?? Date.now()
  const rawId = (item as any).id
  const id = rawId != null
    ? String(rawId)
    : (eventId || buildRiskEventId(actionId, decidedAt) || `risk-${Date.now().toString(36)}`)
  const classification = typeof item.classification === 'string' ? item.classification : ''
  const summaryText = typeof item.summary === 'string' && item.summary.trim().length > 0
    ? item.summary.trim()
    : (actionId === 'A3' ? '风控模型升级至 A3' : '风控模型触发远程警报')
  const displayActionId = resolveRiskDisplayAction(actionId, item, summaryText)
  const channels = resolveRiskChannels(item)
  const place = resolveRiskPlace(channels, item.camChannel || item.cam_channel)
  const soundLightTriggered = typeof (item as any).soundLightTriggered === 'boolean'
    ? (item as any).soundLightTriggered
    : (typeof (item as any).sound_light_triggered === 'boolean' ? (item as any).sound_light_triggered : undefined)
  const snapshots = resolveSnapshots(item)
  const snapshotUrl = snapshots[0] || resolveSnapshotUrl(item)
  return {
    id,
    eventId: typeof eventId === 'string' && eventId ? eventId : undefined,
    level: normalizeRiskLevel(item.level, classification),
    source: place,
    place,
    time: formatAlarmTime(decidedAt),
    summary: summaryText,
    deviceId: `risk:${displayActionId}`,
    occurredAt,
    soundLightTriggered,
    status: typeof item.status === 'string' && item.status ? item.status : '未处理',
    snapshotUrl,
    snapshots
  }
}

export async function loadRiskActions(limit = 50) {
  try {
    const resp = await fetch(`/api/events/risk-actions?limit=${limit}`, { cache: 'no-store' })
    if (!resp.ok) return
    const data = await resp.json()
    if (!Array.isArray(data)) return
    const items = data.slice().reverse()
    for (const item of items) {
      const alarm = mapRiskActionToAlarm(item)
      if (alarm) {
        pushAlarm(alarm)
      }
    }
  } catch {
    // ignore load failures to keep realtime stream functional
  }
}

export async function triggerSoundLightAlarm(action: SoundLightAction = 'activate') {
  if (soundLightMuted.value) {
    return
  }
  try {
    await fetch(`/api/alarm/sound-light/${action}`, { method: 'POST' })
  } catch {}
}

function scheduleSoundLightAutoOff() {
  if (soundLightOffTimer) window.clearTimeout(soundLightOffTimer)
  soundLightOffTimer = window.setTimeout(() => {
    soundLightOffTimer = null
    void triggerSoundLightAlarm('deactivate')
  }, SOUND_LIGHT_AUTO_OFF_MS)
}

export function pushRadarAlarm(data: {
  id?: string | number
  range: number
  speed: number
  place?: string
  angle?: number
}) {
  const rangeStr = Number.isFinite(data.range) ? data.range.toFixed(1) : String(data.range)
  const speedStr = Number.isFinite(data.speed) ? data.speed.toFixed(1) : String(data.speed)
  const angleStr = data.angle != null && Number.isFinite(data.angle)
    ? `${data.angle.toFixed(1)}°`
    : ''
  const alarm: Alarm = {
    id: `radar-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    level: 'major',
    source: '雷达',
    place: data.place || '雷达',
    time: new Date().toLocaleTimeString(),
    summary: `发现目标 距离 ${rangeStr}m 速度 ${speedStr}m/s${angleStr ? ` 角度 ${angleStr}` : ''}`,
  }
  pushAlarm(alarm)
}

export async function refreshSoundLightStatus() {
  try {
    const resp = await fetch('/api/alarm/sound-light/status')
    const data = await resp.json()
    soundLightMuted.value = !!data?.muted
  } catch {
    // keep existing status on failure
  }
}

export function setSoundLightMuted(muted: boolean) {
  soundLightMuted.value = muted
}
