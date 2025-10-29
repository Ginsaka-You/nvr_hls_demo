import { ref } from 'vue'

export type NvrScheme = 'http' | 'https'
export type StreamMode = 'hls' | 'webrtc'
export type DbType = 'mysql' | 'postgres' | 'sqlserver'

export interface SettingsPayload {
  nvrHost: string
  nvrUser: string
  nvrPass: string
  nvrScheme: NvrScheme
  nvrHttpPort: number | null
  portCount: number
  detectMain: boolean
  detectSub: boolean
  streamMode: StreamMode
  hlsOrigin: string
  webrtcServer: string
  webrtcOptions: string
  webrtcPreferCodec: string
  channelOverrides: string
  audioPass: string
  audioId: number
  audioHttpPort: number | null
  radarHost: string
  radarCtrlPort: number
  radarDataPort: number
  radarUseTcp: boolean
  imsiFtpHost: string
  imsiFtpPort: number
  imsiFtpUser: string
  imsiFtpPass: string
  imsiSyncInterval: number
  imsiSyncBatchSize: number
  imsiSyncMaxFiles: number
  imsiFilenameTemplate: string
  imsiLineTemplate: string
  imsiDeviceFilter: string
  dbType: DbType
  dbHost: string
  dbPort: number
  dbName: string
  dbUser: string
  dbPass: string
}

const defaultSettings: SettingsPayload = {
  nvrHost: '192.168.50.76',
  nvrUser: 'admin',
  nvrPass: '00000000a',
  nvrScheme: 'http',
  nvrHttpPort: null,
  portCount: 8,
  detectMain: false,
  detectSub: true,
  streamMode: 'webrtc',
  hlsOrigin: '',
  webrtcServer: 'http://127.0.0.1:8800',
  webrtcOptions: 'transportmode=unicast&profile=Profile_1&forceh264=1&videoCodecType=H264&rtptransport=tcp&timeout=60',
  webrtcPreferCodec: 'video/H264',
  channelOverrides: '',
  audioPass: 'YouloveWill',
  audioId: 12,
  audioHttpPort: 65007,
  radarHost: '192.168.2.40',
  radarCtrlPort: 20000,
  radarDataPort: 20001,
  radarUseTcp: false,
  imsiFtpHost: '47.98.168.56',
  imsiFtpPort: 4721,
  imsiFtpUser: 'ftpuser',
  imsiFtpPass: 'ftpPass@47',
  imsiSyncInterval: 60,
  imsiSyncBatchSize: 500,
  imsiSyncMaxFiles: 6,
  imsiFilenameTemplate: 'CTC_{deviceId}_{dateyymmdd}_{timestamp}.txt',
  imsiLineTemplate: '{deviceId}\\t{imsi}\\t000000000000000\\t{operator:1,2,3,4}\\t{area}\\t{rptTimeyymmdd}\\t{rptTimehhmmss}\\t',
  imsiDeviceFilter: 'njtest001',
  dbType: 'postgres',
  dbHost: '127.0.0.1',
  dbPort: 5432,
  dbName: 'nvr_demo',
  dbUser: 'nvr_app',
  dbPass: 'nvrdemo'
}

export const nvrHost = ref<string>(defaultSettings.nvrHost)
export const nvrUser = ref<string>(defaultSettings.nvrUser)
export const nvrPass = ref<string>(defaultSettings.nvrPass)
export const nvrScheme = ref<NvrScheme>(defaultSettings.nvrScheme)
export const nvrHttpPort = ref<number | null>(defaultSettings.nvrHttpPort)

export const portCount = ref<number>(defaultSettings.portCount)
export const detectMain = ref<boolean>(defaultSettings.detectMain)
export const detectSub = ref<boolean>(defaultSettings.detectSub)

export const streamMode = ref<StreamMode>(defaultSettings.streamMode)
export const hlsOrigin = ref<string>(defaultSettings.hlsOrigin)
export const webrtcServer = ref<string>(defaultSettings.webrtcServer)
export const webrtcOptions = ref<string>(defaultSettings.webrtcOptions)
export const webrtcPreferCodec = ref<string>(defaultSettings.webrtcPreferCodec)
export const channelOverrides = ref<string>(defaultSettings.channelOverrides)

export const audioPass = ref<string>(defaultSettings.audioPass)
export const audioId = ref<number>(defaultSettings.audioId)
export const audioHttpPort = ref<number | null>(defaultSettings.audioHttpPort)

export const radarHost = ref<string>(defaultSettings.radarHost)
export const radarCtrlPort = ref<number>(defaultSettings.radarCtrlPort)
export const radarDataPort = ref<number>(defaultSettings.radarDataPort)
export const radarUseTcp = ref<boolean>(defaultSettings.radarUseTcp)

export const imsiFtpHost = ref<string>(defaultSettings.imsiFtpHost)
export const imsiFtpPort = ref<number>(defaultSettings.imsiFtpPort)
export const imsiFtpUser = ref<string>(defaultSettings.imsiFtpUser)
export const imsiFtpPass = ref<string>(defaultSettings.imsiFtpPass)
export const imsiSyncInterval = ref<number>(defaultSettings.imsiSyncInterval)
export const imsiSyncBatchSize = ref<number>(defaultSettings.imsiSyncBatchSize)
export const imsiSyncMaxFiles = ref<number>(defaultSettings.imsiSyncMaxFiles)
export const imsiFilenameTemplate = ref<string>(defaultSettings.imsiFilenameTemplate)
export const imsiLineTemplate = ref<string>(defaultSettings.imsiLineTemplate)
export const imsiDeviceFilter = ref<string>(defaultSettings.imsiDeviceFilter)

export const dbType = ref<DbType>(defaultSettings.dbType)
export const dbHost = ref<string>(defaultSettings.dbHost)
export const dbPort = ref<number>(defaultSettings.dbPort)
export const dbName = ref<string>(defaultSettings.dbName)
export const dbUser = ref<string>(defaultSettings.dbUser)
export const dbPass = ref<string>(defaultSettings.dbPass)

let extraSettings: Record<string, unknown> = {}
let loadPromise: Promise<void> | null = null
let hasLoaded = false
let lastError: string | null = null

function toStringValue(value: unknown, fallback: string): string {
  if (value === null || value === undefined) return fallback
  return String(value)
}

function toEnum<T extends string>(value: unknown, allowed: readonly T[], fallback: T): T {
  if (typeof value === 'string') {
    const directMatch = allowed.find(item => item === value)
    if (directMatch) return directMatch
    const lowered = value.toLowerCase()
    const loweredMatch = allowed.find(item => item === lowered)
    if (loweredMatch) return loweredMatch
  }
  return fallback
}

function toBoolean(value: unknown, fallback: boolean): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    const normalized = value.trim().toLowerCase()
    if (['true', '1', 'yes'].includes(normalized)) return true
    if (['false', '0', 'no'].includes(normalized)) return false
  }
  if (typeof value === 'number') return value !== 0
  return fallback
}

function toInt(value: unknown, fallback: number): number {
  if (value === null || value === undefined || value === '') return fallback
  const num = Number(value)
  return Number.isFinite(num) ? Math.trunc(num) : fallback
}

function toNullableInt(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const num = Number(value)
  return Number.isFinite(num) ? Math.trunc(num) : null
}

function applySettings(payload: Record<string, unknown> | null | undefined) {
  if (!payload || typeof payload !== 'object') {
    extraSettings = {}
    return
  }

  const unknown: Record<string, unknown> = {}
  Object.entries(payload).forEach(([key, raw]) => {
    switch (key) {
      case 'nvrHost':
        nvrHost.value = toStringValue(raw, defaultSettings.nvrHost)
        break
      case 'nvrUser':
        nvrUser.value = toStringValue(raw, defaultSettings.nvrUser)
        break
      case 'nvrPass':
        nvrPass.value = toStringValue(raw, defaultSettings.nvrPass)
        break
      case 'nvrScheme':
        nvrScheme.value = toEnum<NvrScheme>(raw, ['http', 'https'], defaultSettings.nvrScheme)
        break
      case 'nvrHttpPort':
        nvrHttpPort.value = toNullableInt(raw)
        break
      case 'portCount':
        portCount.value = toInt(raw, defaultSettings.portCount)
        break
      case 'detectMain':
        detectMain.value = toBoolean(raw, defaultSettings.detectMain)
        break
      case 'detectSub':
        detectSub.value = toBoolean(raw, defaultSettings.detectSub)
        break
      case 'streamMode':
        streamMode.value = toEnum<StreamMode>(raw, ['hls', 'webrtc'], defaultSettings.streamMode)
        break
      case 'hlsOrigin':
        hlsOrigin.value = toStringValue(raw, defaultSettings.hlsOrigin)
        break
      case 'webrtcServer':
        webrtcServer.value = toStringValue(raw, defaultSettings.webrtcServer)
        break
      case 'webrtcOptions':
        webrtcOptions.value = toStringValue(raw, defaultSettings.webrtcOptions)
        break
      case 'webrtcPreferCodec':
        webrtcPreferCodec.value = toStringValue(raw, defaultSettings.webrtcPreferCodec)
        break
      case 'channelOverrides':
        channelOverrides.value = toStringValue(raw, defaultSettings.channelOverrides)
        break
      case 'audioPass':
        audioPass.value = toStringValue(raw, defaultSettings.audioPass)
        break
      case 'audioId':
        audioId.value = toInt(raw, defaultSettings.audioId)
        break
      case 'audioHttpPort':
        audioHttpPort.value = toNullableInt(raw)
        break
      case 'radarHost':
        radarHost.value = toStringValue(raw, defaultSettings.radarHost)
        break
      case 'radarCtrlPort':
        radarCtrlPort.value = toInt(raw, defaultSettings.radarCtrlPort)
        break
      case 'radarDataPort':
        radarDataPort.value = toInt(raw, defaultSettings.radarDataPort)
        break
      case 'radarUseTcp':
        radarUseTcp.value = toBoolean(raw, defaultSettings.radarUseTcp)
        break
      case 'imsiFtpHost':
        imsiFtpHost.value = toStringValue(raw, defaultSettings.imsiFtpHost)
        break
      case 'imsiFtpPort':
        imsiFtpPort.value = toInt(raw, defaultSettings.imsiFtpPort)
        break
      case 'imsiFtpUser':
        imsiFtpUser.value = toStringValue(raw, defaultSettings.imsiFtpUser)
        break
      case 'imsiFtpPass':
        imsiFtpPass.value = toStringValue(raw, defaultSettings.imsiFtpPass)
        break
      case 'imsiSyncInterval':
        imsiSyncInterval.value = toInt(raw, defaultSettings.imsiSyncInterval)
        break
      case 'imsiSyncBatchSize':
        imsiSyncBatchSize.value = toInt(raw, defaultSettings.imsiSyncBatchSize)
        break
      case 'imsiSyncMaxFiles':
        imsiSyncMaxFiles.value = toInt(raw, defaultSettings.imsiSyncMaxFiles)
        break
      case 'imsiFilenameTemplate':
        imsiFilenameTemplate.value = toStringValue(raw, defaultSettings.imsiFilenameTemplate)
        break
      case 'imsiLineTemplate':
        imsiLineTemplate.value = toStringValue(raw, defaultSettings.imsiLineTemplate)
        break
      case 'imsiDeviceFilter':
        imsiDeviceFilter.value = toStringValue(raw, defaultSettings.imsiDeviceFilter)
        break
      case 'dbType':
        dbType.value = toEnum<DbType>(raw, ['mysql', 'postgres', 'sqlserver'], defaultSettings.dbType)
        break
      case 'dbHost':
        dbHost.value = toStringValue(raw, defaultSettings.dbHost)
        break
      case 'dbPort':
        dbPort.value = toInt(raw, defaultSettings.dbPort)
        break
      case 'dbName':
        dbName.value = toStringValue(raw, defaultSettings.dbName)
        break
      case 'dbUser':
        dbUser.value = toStringValue(raw, defaultSettings.dbUser)
        break
      case 'dbPass':
        dbPass.value = toStringValue(raw, defaultSettings.dbPass)
        break
      default:
        unknown[key] = raw
    }
  })
  extraSettings = unknown
}

function sanitizeString(value: unknown, trim = false): string {
  const text = value === null || value === undefined ? '' : String(value)
  return trim ? text.trim() : text
}

function sanitizeInt(value: unknown, fallback: number): number {
  const num = Number(value)
  return Number.isFinite(num) ? Math.trunc(num) : fallback
}

function sanitizeOptionalInt(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const num = Number(value)
  return Number.isFinite(num) ? Math.trunc(num) : null
}

function buildPayload(): SettingsPayload & Record<string, unknown> {
  const payload: SettingsPayload = {
    nvrHost: sanitizeString(nvrHost.value, true),
    nvrUser: sanitizeString(nvrUser.value, true),
    nvrPass: sanitizeString(nvrPass.value),
    nvrScheme: nvrScheme.value === 'https' ? 'https' : 'http',
    nvrHttpPort: sanitizeOptionalInt(nvrHttpPort.value),
    portCount: sanitizeInt(portCount.value, defaultSettings.portCount),
    detectMain: !!detectMain.value,
    detectSub: !!detectSub.value,
    streamMode: streamMode.value === 'hls' ? 'hls' : 'webrtc',
    hlsOrigin: sanitizeString(hlsOrigin.value),
    webrtcServer: sanitizeString(webrtcServer.value, true),
    webrtcOptions: sanitizeString(webrtcOptions.value),
    webrtcPreferCodec: sanitizeString(webrtcPreferCodec.value),
    channelOverrides: sanitizeString(channelOverrides.value),
    audioPass: sanitizeString(audioPass.value),
    audioId: sanitizeInt(audioId.value, defaultSettings.audioId),
    audioHttpPort: sanitizeOptionalInt(audioHttpPort.value),
    radarHost: sanitizeString(radarHost.value, true),
    radarCtrlPort: sanitizeInt(radarCtrlPort.value, defaultSettings.radarCtrlPort),
    radarDataPort: sanitizeInt(radarDataPort.value, defaultSettings.radarDataPort),
    radarUseTcp: !!radarUseTcp.value,
    imsiFtpHost: sanitizeString(imsiFtpHost.value, true),
    imsiFtpPort: sanitizeInt(imsiFtpPort.value, defaultSettings.imsiFtpPort),
    imsiFtpUser: sanitizeString(imsiFtpUser.value, true),
    imsiFtpPass: sanitizeString(imsiFtpPass.value),
    imsiSyncInterval: sanitizeInt(imsiSyncInterval.value, defaultSettings.imsiSyncInterval),
    imsiSyncBatchSize: sanitizeInt(imsiSyncBatchSize.value, defaultSettings.imsiSyncBatchSize),
    imsiSyncMaxFiles: sanitizeInt(imsiSyncMaxFiles.value, defaultSettings.imsiSyncMaxFiles),
    imsiFilenameTemplate: sanitizeString(imsiFilenameTemplate.value),
    imsiLineTemplate: sanitizeString(imsiLineTemplate.value),
    imsiDeviceFilter: sanitizeString(imsiDeviceFilter.value, true),
    dbType: ['mysql', 'sqlserver'].includes(dbType.value) ? dbType.value : 'postgres',
    dbHost: sanitizeString(dbHost.value, true),
    dbPort: sanitizeInt(dbPort.value, defaultSettings.dbPort),
    dbName: sanitizeString(dbName.value, true),
    dbUser: sanitizeString(dbUser.value, true),
    dbPass: sanitizeString(dbPass.value)
  }
  return { ...extraSettings, ...payload }
}

export function getSettingsLoadError(): string | null {
  return lastError
}

export async function ensureSettingsLoaded(forceReload = false): Promise<void> {
  if (forceReload) {
    hasLoaded = false
    loadPromise = null
  }
  if (hasLoaded && !forceReload) {
    return
  }
  if (!loadPromise) {
    loadPromise = (async () => {
      try {
        const resp = await fetch('/api/config', { method: 'GET', cache: 'no-store' })
        if (!resp.ok) {
          throw new Error(`HTTP ${resp.status}`)
        }
        const data: Record<string, unknown> = await resp.json().catch(() => ({}))
        applySettings(data)
        lastError = null
      } catch (error: any) {
        lastError = error?.message || String(error)
        console.warn('Failed to load settings config:', lastError)
      } finally {
        hasLoaded = true
        loadPromise = null
      }
    })()
  }
  await loadPromise
}

export async function saveSettings(): Promise<SettingsPayload> {
  const payload = buildPayload()
  const body = JSON.stringify(payload)
  let responseBody: SettingsPayload | Record<string, unknown> = payload
  try {
    const resp = await fetch('/api/config', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body
    })
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`)
    }
    responseBody = await resp.json().catch(() => payload)
    applySettings(responseBody)
    lastError = null
    hasLoaded = true
  } catch (error: any) {
    lastError = error?.message || String(error)
    console.error('Failed to save settings config:', lastError)
    throw error
  }
  return responseBody as SettingsPayload
}
