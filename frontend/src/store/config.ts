import { ref } from 'vue'

export const nvrHost = ref<string>('192.168.50.76')
export const nvrUser = ref<string>('admin')
export const nvrPass = ref<string>('00000000a')
export const nvrScheme = ref<'http'|'https'>('http')
export const nvrHttpPort = ref<number | null>(null)

// Auto-detect options
export const portCount = ref<number>(8)
export const detectMain = ref<boolean>(false)
export const detectSub = ref<boolean>(true)

// Stream delivery (HLS vs WebRTC)
export const streamMode = ref<'hls' | 'webrtc'>('webrtc')
export const hlsOrigin = ref<string>('')
export const webrtcServer = ref<string>('http://127.0.0.1:8800')
export const webrtcOptions = ref<string>('transportmode=unicast&profile=Profile_1&forceh264=1&videoCodecType=H264&rtptransport=tcp&timeout=60')
export const webrtcPreferCodec = ref<string>('video/H264')
export const channelOverrides = ref<string>('')

// Optionally extend later: scheme/httpPort

// Alarm linkage: audio alarm on camera
// pass here may be different from NVR login
export const audioPass = ref<string>('YouloveWill')
// Default camera ID (port) to trigger when event lacks port
export const audioId = ref<number>(12)
// Camera HTTP port for audio alarm triggering (not NVR port)
export const audioHttpPort = ref<number | null>(65007)

// Radar connectivity
export const radarHost = ref<string>('192.168.2.100')
export const radarCtrlPort = ref<number>(20000)
export const radarDataPort = ref<number>(20001)
export const radarUseTcp = ref<boolean>(true)

// Database connection
export const dbType = ref<'mysql'|'postgres'|'sqlserver'>('postgres')
export const dbHost = ref<string>('127.0.0.1')
export const dbPort = ref<number>(5432)
export const dbName = ref<string>('nvr_demo')
export const dbUser = ref<string>('nvr_app')
export const dbPass = ref<string>('nvrdemo')
