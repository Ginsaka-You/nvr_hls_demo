import { readonly, ref, watch, WatchStopHandle } from 'vue'
import { nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort, portCount, radarHost, radarCtrlPort, radarDataPort, radarUseTcp } from './config'

export type DeviceState = {
  status: 'unknown' | 'ok' | 'error'
  message: string
  failureCount: number
}

const DETECT_INTERVAL_MS = 60000
const FAILURE_THRESHOLD = 3

const radarState = ref<DeviceState>({ status: 'unknown', message: '正在检测...', failureCount: 0 })
const cameraState = ref<DeviceState>({ status: 'unknown', message: '正在检测...', failureCount: 0 })

let connected = false
let radarTimer: number | null = null
let cameraTimer: number | null = null
let radarWatchStop: WatchStopHandle | null = null
let cameraWatchStop: WatchStopHandle | null = null
let radarConfigDebounce: number | null = null
let cameraConfigDebounce: number | null = null

function normalizeError(message: any) {
  const text = (message || '').toString()
  if (!text) return '连接失败'
  if (text.includes('Failed to fetch') || text.includes('fetch')) return '连接失败'
  return text
}

async function runRadarCheck() {
  const host = (radarHost.value || '').trim()
  if (!host) {
    radarState.value = { status: 'error', message: '未配置', failureCount: FAILURE_THRESHOLD }
    return
  }
  try {
    const ctrl = Number(radarCtrlPort.value) || 20000
    const data = Number(radarDataPort.value) || ctrl
    const useTcp = !!radarUseTcp.value
    const resp = await fetch('/api/radar/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ host, ports: [ctrl, data], timeoutMs: 2000, useTcp })
    })
    const json: any = await resp.json().catch(() => ({}))
    if (json?.ok) {
      radarState.value = { status: 'ok', message: '连接正常', failureCount: 0 }
    } else {
      const count = Math.min(FAILURE_THRESHOLD, radarState.value.failureCount + 1)
      radarState.value = {
        status: 'error',
        message: normalizeError(json?.error),
        failureCount: count
      }
    }
  } catch (e: any) {
    const count = Math.min(FAILURE_THRESHOLD, radarState.value.failureCount + 1)
    radarState.value = {
      status: 'error',
      message: normalizeError(e?.message),
      failureCount: count
    }
  }
}

async function detectCameraPorts(): Promise<number[]> {
  const host = (nvrHost.value || '').trim()
  const user = (nvrUser.value || '').trim()
  const pass = (nvrPass.value || '').trim()
  const params = new URLSearchParams({ host, user, pass, scheme: nvrScheme.value })
  if (nvrHttpPort.value) params.set('httpPort', String(nvrHttpPort.value))
  try {
    const resp = await fetch(`/api/nvr/channels?${params.toString()}`)
    if (!resp.ok) return []
    const json: any = await resp.json().catch(() => ({}))
    if (json?.ok) {
      const ports = new Set<number>()
      const channels: any[] = Array.isArray(json.channels) ? json.channels : []
      channels.forEach(ch => {
        const id = Number(ch?.id || ch?.channelId || ch?.index)
        if (!Number.isFinite(id)) return
        const port = Math.floor(id / 100)
        ports.add(port > 0 ? port : id)
      })
      const normalized = Array.from(ports).filter(p => p > 0)
      if (normalized.length) return normalized.sort((a, b) => a - b)
    }
  } catch {}
  const fallback = Math.max(8, portCount.value || 0)
  return Array.from({ length: fallback }, (_, i) => i + 1)
}

async function startCameraStream(id: string, host: string, user: string, pass: string): Promise<boolean> {
  const digits = id.match(/\d+/)?.[0] || ''
  if (!digits) return false
  const rtsp = `rtsp://${user}:${encodeURIComponent(pass)}@${host}:554/Streaming/Channels/${digits}`
  try {
    const resp = await fetch(`/api/streams/${id}/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `rtspUrl=${encodeURIComponent(rtsp)}`
    })
    if (!resp.ok) return false
    return waitStreamReady(id)
  } catch {
    return false
  }
}

async function waitStreamReady(id: string, timeoutMs = 5000, pollMs = 400) {
  const started = Date.now()
  while (Date.now() - started < timeoutMs) {
    try {
      const resp = await fetch(`/api/streams/${id}/status`)
      if (resp.ok) {
        const json = await resp.json()
        if (json?.probe?.m3u8Exists) return true
      }
    } catch {}
    await new Promise(resolve => setTimeout(resolve, pollMs))
  }
  return false
}

async function startAllCameraStreams(ports: number[], host: string, user: string, pass: string): Promise<number> {
  const maxPorts = Math.max(8, portCount.value || 0)
  const attempts = Math.min(ports.length || maxPorts, maxPorts)
  const tasks = ports.slice(0, attempts).map(async port => {
    if (!port) return false
    const subId = `cam${port}02`
    if (await startCameraStream(subId, host, user, pass)) return true
    const mainId = `cam${port}01`
    return startCameraStream(mainId, host, user, pass)
  })
  const results = await Promise.all(tasks)
  return results.filter(Boolean).length
}

async function runCameraCheck() {
  const host = (nvrHost.value || '').trim()
  const user = (nvrUser.value || '').trim()
  const pass = (nvrPass.value || '').trim()
  if (!host || !user || !pass) {
    cameraState.value = { status: 'error', message: '未配置', failureCount: FAILURE_THRESHOLD }
    return
  }
  try {
    const totalPorts = await detectCameraPorts()
    if (!totalPorts.length) {
      cameraState.value = { status: 'error', message: '无可用通道', failureCount: FAILURE_THRESHOLD }
      return
    }
    const started = await startAllCameraStreams(totalPorts, host, user, pass)
    if (started > 0) {
      cameraState.value = { status: 'ok', message: `运行 ${started} 路`, failureCount: 0 }
    } else {
      const count = Math.min(FAILURE_THRESHOLD, cameraState.value.failureCount + 1)
      cameraState.value = {
        status: 'error',
        message: '启动失败',
        failureCount: count
      }
    }
  } catch (e: any) {
    const count = Math.min(FAILURE_THRESHOLD, cameraState.value.failureCount + 1)
    cameraState.value = {
      status: 'error',
      message: normalizeError(e?.message),
      failureCount: count
    }
  }
}

function startTimers() {
  if (radarTimer) window.clearInterval(radarTimer)
  radarTimer = window.setInterval(() => { void runRadarCheck() }, DETECT_INTERVAL_MS)
  if (cameraTimer) window.clearInterval(cameraTimer)
  cameraTimer = window.setInterval(() => { void runCameraCheck() }, DETECT_INTERVAL_MS)
}

function setupConfigWatchers() {
  if (radarWatchStop) radarWatchStop()
  if (cameraWatchStop) cameraWatchStop()

  radarWatchStop = watch([radarHost, radarCtrlPort, radarDataPort, radarUseTcp], () => {
    if (radarConfigDebounce) window.clearTimeout(radarConfigDebounce)
    radarConfigDebounce = window.setTimeout(() => {
      radarState.value = { status: 'unknown', message: '正在检测...', failureCount: 0 }
      void runRadarCheck()
    }, 300)
  })

  cameraWatchStop = watch([nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort, portCount], () => {
    if (cameraConfigDebounce) window.clearTimeout(cameraConfigDebounce)
    cameraConfigDebounce = window.setTimeout(() => {
      cameraState.value = { status: 'unknown', message: '正在检测...', failureCount: 0 }
      void runCameraCheck()
    }, 300)
  })
}

export function connectDeviceMonitoring() {
  if (connected) return
  connected = true
  void runRadarCheck()
  void runCameraCheck()
  startTimers()
  setupConfigWatchers()
}

export function disconnectDeviceMonitoring() {
  connected = false
  if (radarTimer) window.clearInterval(radarTimer)
  if (cameraTimer) window.clearInterval(cameraTimer)
  radarTimer = null
  cameraTimer = null
  if (radarWatchStop) radarWatchStop()
  if (cameraWatchStop) cameraWatchStop()
  radarWatchStop = null
  cameraWatchStop = null
  if (radarConfigDebounce) window.clearTimeout(radarConfigDebounce)
  if (cameraConfigDebounce) window.clearTimeout(cameraConfigDebounce)
  radarConfigDebounce = null
  cameraConfigDebounce = null
}

export const radarDeviceState = readonly(radarState)
export const cameraDeviceState = readonly(cameraState)
export const radarFailureThreshold = FAILURE_THRESHOLD
export const cameraFailureThreshold = FAILURE_THRESHOLD
