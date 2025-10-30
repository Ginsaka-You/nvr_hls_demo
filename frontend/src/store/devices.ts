import { readonly, ref, watch, WatchStopHandle } from 'vue'
import { radarHost, radarCtrlPort, radarDataPort, radarUseTcp, nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort, portCount, streamMode, webrtcServer, imsiFtpHost, imsiFtpPort, imsiFtpUser, imsiFtpPass } from './config'
import { cameraHealth, resetCameraHealth } from './cameraHealth'

export type DeviceState = {
  status: 'unknown' | 'ok' | 'error'
  message: string
  failureCount: number
}

const DETECT_INTERVAL_MS = 60000
const FAILURE_THRESHOLD = 3

const radarState = ref<DeviceState>({ status: 'unknown', message: '正在检测...', failureCount: 0 })
const cameraState = ref<DeviceState>({ status: 'unknown', message: '正在检测...', failureCount: 0 })
const imsiState = ref<DeviceState>({ status: 'unknown', message: '正在检测...', failureCount: 0 })

let connected = false
let radarTimer: number | null = null
let cameraTimer: number | null = null
let imsiTimer: number | null = null
let radarWatchStop: WatchStopHandle | null = null
let cameraWatchStop: WatchStopHandle | null = null
let imsiWatchStop: WatchStopHandle | null = null
let radarConfigDebounce: number | null = null
let cameraConfigDebounce: number | null = null
let imsiConfigDebounce: number | null = null

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
      body: JSON.stringify({
        host,
        controlPort: ctrl,
        dataPort: data,
        ports: [ctrl, data],
        timeoutMs: 2000,
        useTcp
      })
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

async function runImsiCheck() {
  const host = (imsiFtpHost.value || '').trim()
  const user = (imsiFtpUser.value || '').trim()
  const pass = (imsiFtpPass.value || '').trim()
  const port = Number(imsiFtpPort.value) || 21
  if (!host || !user || !pass) {
    imsiState.value = { status: 'error', message: '未配置', failureCount: FAILURE_THRESHOLD }
    return
  }
  try {
    const resp = await fetch('/api/imsi/test-ftp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ host, port, user, pass, timeoutMs: 4000 })
    })
    const json: any = await resp.json().catch(() => ({}))
    if (json?.ok) {
      imsiState.value = { status: 'ok', message: '连接正常', failureCount: 0 }
    } else {
      const count = Math.min(FAILURE_THRESHOLD, imsiState.value.failureCount + 1)
      const msg = json?.message ? String(json.message) : '连接失败'
      imsiState.value = { status: 'error', message: normalizeError(msg), failureCount: count }
    }
  } catch (e: any) {
    const count = Math.min(FAILURE_THRESHOLD, imsiState.value.failureCount + 1)
    imsiState.value = {
      status: 'error',
      message: normalizeError(e?.message),
      failureCount: count
    }
  }
}

function startTimers() {
  if (radarTimer) window.clearInterval(radarTimer)
  radarTimer = window.setInterval(() => { void runRadarCheck() }, DETECT_INTERVAL_MS)
  if (imsiTimer) window.clearInterval(imsiTimer)
  imsiTimer = window.setInterval(() => { void runImsiCheck() }, DETECT_INTERVAL_MS)
}

function setupConfigWatchers() {
  if (radarWatchStop) radarWatchStop()

  radarWatchStop = watch([radarHost, radarCtrlPort, radarDataPort, radarUseTcp], () => {
    if (radarConfigDebounce) window.clearTimeout(radarConfigDebounce)
    radarConfigDebounce = window.setTimeout(() => {
      radarState.value = { status: 'unknown', message: '正在检测...', failureCount: 0 }
      void runRadarCheck()
    }, 300)
  })

  if (imsiWatchStop) imsiWatchStop()
  imsiWatchStop = watch([imsiFtpHost, imsiFtpPort, imsiFtpUser, imsiFtpPass], () => {
    if (imsiConfigDebounce) window.clearTimeout(imsiConfigDebounce)
    imsiConfigDebounce = window.setTimeout(() => {
      imsiState.value = { status: 'unknown', message: '正在检测...', failureCount: 0 }
      void runImsiCheck()
    }, 300)
  })
}

export function connectDeviceMonitoring() {
  if (connected) return
  connected = true
  void runRadarCheck()
  void runImsiCheck()
  cameraState.value = { status: 'unknown', message: '正在检测...', failureCount: 0 }
  resetCameraHealth()
  startTimers()
  setupConfigWatchers()

  cameraWatchStop = watch(
    [cameraHealth.status, cameraHealth.message, cameraHealth.available, cameraHealth.total],
    () => {
      const statusValue = cameraHealth.status.value
      const messageValue = cameraHealth.message.value
      const failureCount = statusValue === 'error' ? FAILURE_THRESHOLD : 0
      cameraState.value = { status: statusValue, message: messageValue, failureCount }
    },
    { immediate: true }
  )

  watch([nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort, portCount, streamMode, webrtcServer], () => {
    if (cameraConfigDebounce) window.clearTimeout(cameraConfigDebounce)
    cameraConfigDebounce = window.setTimeout(() => {
      resetCameraHealth()
      cameraState.value = { status: 'unknown', message: '正在检测...', failureCount: 0 }
    }, 300)
  })
}

export function disconnectDeviceMonitoring() {
  connected = false
  if (radarTimer) window.clearInterval(radarTimer)
  radarTimer = null
  if (imsiTimer) window.clearInterval(imsiTimer)
  imsiTimer = null
  if (radarWatchStop) radarWatchStop()
  radarWatchStop = null
  if (radarConfigDebounce) window.clearTimeout(radarConfigDebounce)
  radarConfigDebounce = null
  if (cameraWatchStop) cameraWatchStop()
  cameraWatchStop = null
  if (cameraConfigDebounce) window.clearTimeout(cameraConfigDebounce)
  cameraConfigDebounce = null
  if (imsiWatchStop) imsiWatchStop()
  imsiWatchStop = null
  if (imsiConfigDebounce) window.clearTimeout(imsiConfigDebounce)
  imsiConfigDebounce = null
}

export const radarDeviceState = readonly(radarState)
export const cameraDeviceState = readonly(cameraState)
export const imsiDeviceState = readonly(imsiState)
export const radarFailureThreshold = FAILURE_THRESHOLD
export const cameraFailureThreshold = FAILURE_THRESHOLD
export const imsiFailureThreshold = FAILURE_THRESHOLD
