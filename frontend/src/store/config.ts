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

// Optionally extend later: scheme/httpPort

// Alarm linkage: audio alarm on camera
// pass here may be different from NVR login
export const audioPass = ref<string>('YouloveWill')
// Default camera ID (port) to trigger when event lacks port
export const audioId = ref<number>(12)
// Camera HTTP port for audio alarm triggering (not NVR port)
export const audioHttpPort = ref<number | null>(65007)
