import { ref, Ref, watch } from 'vue'
import { nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort } from './config'

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
  alarms.value = [a, ...alarms.value].slice(0, 200)
}

