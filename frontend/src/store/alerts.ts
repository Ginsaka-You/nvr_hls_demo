import { ref, Ref, watch } from 'vue'
import { nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort, audioPass, audioId, audioHttpPort } from './config'

export type Alarm = {
  id: string
  level: 'info'|'minor'|'major'|'critical'
  source: string
  place: string
  time: string
  summary: string
  deviceId?: string
}

export const alarms: Ref<Alarm[]> = ref([])

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

function pushAlarm(a: Alarm, options: { playAudio?: boolean } = {}) {
  const existed = alarms.value.some(item => item.id === a.id)
  alarms.value = [a, ...alarms.value.filter(item => item.id !== a.id)].slice(0, 200)
  if (!existed && options.playAudio) void triggerCameraAudio()
}

let esPush: EventSource | null = null
let connected = false

export function connectAlerts() {
  if (connected) return
  connected = true
  openStreams()
  // Reconnect on config changes
  watch([nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort], () => {
    closeStreams()
    openStreams()
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
  const a: Alarm = {
    id: ev?.id || Math.random().toString(36).slice(2),
    level: (ev?.level || 'major') as any,
    source: '摄像头',
    place: camChannel ? camChannel : '摄像头',
    time: new Date().toLocaleTimeString(),
    summary,
    deviceId: camId
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

function pushRiskAlarm(data: any) {
  const id = typeof data?.id === 'string' && data.id ? data.id : `risk-${Date.now().toString(36)}`
  const actionId = typeof data?.actionId === 'string' && data.actionId ? data.actionId : 'A2'
  const classification = typeof data?.classification === 'string' && data.classification ? data.classification : ''
  const scoreValue = typeof data?.score === 'number' && Number.isFinite(data.score)
    ? data.score
    : (typeof data?.score === 'string' ? Number(data.score) : NaN)
  const scoreText = Number.isFinite(scoreValue) ? `综合得分 ${scoreValue.toFixed(1)}` : ''
  const upgrade = data?.upgrade === true
  const nightMode = data?.nightMode === true
  const cooldownSecondsRaw = typeof data?.audioCooldownSeconds === 'number' ? data.audioCooldownSeconds : Number(data?.audioCooldownSeconds)
  const cooldownSeconds = Number.isFinite(cooldownSecondsRaw) ? Math.max(0, cooldownSecondsRaw) : null
  const shouldPlayAudio = data?.triggerAudio !== false
  const summaryText = typeof data?.summary === 'string' && data.summary.trim().length > 0
    ? data.summary.trim()
    : '风控模型触发远程警报'
  const rationale = typeof data?.rationale === 'string' && data.rationale.trim().length > 0
    ? data.rationale.trim()
    : ''
  const cooldownText = !shouldPlayAudio && cooldownSeconds && cooldownSeconds > 0
    ? `音频冷却中（约 ${Math.ceil(cooldownSeconds)} 秒）`
    : ''
  const upgradeText = upgrade ? '已升级至 A3' : ''
  const nightLabel = nightMode ? '夜间模式' : ''
  const detailParts = [summaryText, rationale, scoreText, classification ? `优先级 ${classification}` : '', upgradeText, nightLabel, cooldownText]
    .filter(Boolean)
  const channels = sanitizeChannels(data?.channels)
  const place = channels.length ? `摄像头 ${channels.join(',')}` : '风控模型'
  const decidedAt = typeof data?.decidedAt === 'string' && data.decidedAt
    ? new Date(data.decidedAt)
    : null
  const time = decidedAt && !Number.isNaN(decidedAt.getTime())
    ? decidedAt.toLocaleTimeString()
    : new Date().toLocaleTimeString()
  const alarm: Alarm = {
    id,
    level: normalizeRiskLevel(data?.level, classification),
    source: '风控模型',
    place,
    time,
    summary: detailParts.join(' ｜ '),
    deviceId: `risk:${actionId}`
  }
  pushAlarm(alarm, { playAudio: shouldPlayAudio })
}

export async function triggerCameraAudio() {
  const id = (audioId.value || 1)
  const pass = (audioPass.value || nvrPass.value || '').trim()
  const host = (nvrHost.value || '').trim()
  const user = (nvrUser.value || '').trim()
  const scheme = nvrScheme.value || 'http'
  const httpPort = audioHttpPort.value
  if (!host || !user || !pass || !id) return
  const p = new URLSearchParams({ host, user, pass, scheme, id: String(id) })
  if (httpPort) p.set('httpPort', String(httpPort))
  try {
    await fetch('/api/nvr/ipc/audioAlarm/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: p.toString()
    })
  } catch {}
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
  persistRadarAlert(alarm, data)
}

async function persistRadarAlert(alarm: Alarm, data: { range: number; speed: number; angle?: number; id?: string | number }) {
  try {
    const payload = JSON.stringify({
      id: alarm.id,
      summary: alarm.summary,
      range: data.range,
      speed: data.speed,
      angle: data.angle ?? null
    })
    await fetch('/api/alerts/manual', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        eventId: alarm.id,
        eventType: 'radar',
        level: alarm.level,
        payload,
        data: payload,
        eventTime: new Date().toISOString()
      })
    })
  } catch {}
}
