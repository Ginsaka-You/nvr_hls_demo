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

function pushAlarm(a: Alarm) {
  alarms.value = [a, ...alarms.value].slice(0, 200)
}

let esPush: EventSource | null = null
let esPull: EventSource | null = null
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
  try { if (esPull) { esPull.close(); esPull = null } } catch {}

  // Push (device -> server)
  try {
    esPush = new EventSource(`/api/nvr/alerts/subscribe`)
    esPush.onmessage = (ev) => handleEvent(ev)
    esPush.onerror = () => { try { esPush && esPush.close() } catch {}; esPush = null; setTimeout(openStreams, 3000) }
  } catch {}

  // Pull (server -> device) — optional fallback
  try {
    const params = new URLSearchParams({ host: nvrHost.value, user: nvrUser.value, pass: nvrPass.value, scheme: nvrScheme.value })
    if (nvrHttpPort.value) params.set('httpPort', String(nvrHttpPort.value))
    esPull = new EventSource(`/api/nvr/alerts/stream?${params.toString()}`)
    esPull.onmessage = (ev) => handleEvent(ev)
    esPull.onerror = () => { try { esPull && esPull.close() } catch {}; esPull = null; setTimeout(openStreams, 3000) }
  } catch {}
}

function closeStreams() {
  try { esPush && esPush.close() } catch {}
  try { esPull && esPull.close() } catch {}
  esPush = null
  esPull = null
}

function handleEvent(ev: MessageEvent) {
  try {
    const data = JSON.parse((ev as any).data)
    if (data && data.type === 'alert') {
      pushAlarmFromEvent(data)
    }
  } catch {}
}

function mapEventType(et: string) {
  const s = et.toLowerCase()
  if (s.includes('field') || s.includes('intrusion')) return '区域入侵告警'
  if (s.includes('linedetection') || s.includes('tripwire')) return '越界侦测告警'
  if (s.includes('vmd') || s.includes('motion')) return '移动侦测告警'
  return et
}

export function pushAlarmFromEvent(ev: any) {
  const port: number | undefined = ev?.port
  const ch = typeof port === 'number' ? port : undefined
  const camId = ch ? `cam${ch}02` : undefined
  const et: string = (ev?.eventType || '').toString()
  const summary = et ? mapEventType(et) : '事件告警'
  const a: Alarm = {
    id: ev?.id || Math.random().toString(36).slice(2),
    level: (ev?.level || 'major') as any,
    source: 'camera',
    place: ch ? `摄像头 ${ch}` : '摄像头',
    time: new Date().toLocaleTimeString(),
    summary,
    deviceId: camId
  }
  pushAlarm(a)

  // Trigger camera audio alarm using configured ID (not NVR port)
  try { triggerCameraAudio() } catch {}
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
    source: 'radar',
    place: data.place || '相控阵雷达',
    time: new Date().toLocaleTimeString(),
    summary: `发现目标 距离 ${rangeStr}m 速度 ${speedStr}m/s${angleStr ? ` 角度 ${angleStr}` : ''}`,
  }
  pushAlarm(alarm)
}
