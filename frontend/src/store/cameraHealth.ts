import { ref } from 'vue'

export type CameraHealthState = {
  status: 'unknown' | 'ok' | 'error'
  message: string
  available: number
  total: number
  lastUpdated: number | null
}

const status = ref<CameraHealthState['status']>('unknown')
const message = ref('正在检测...')
const available = ref(0)
const total = ref(0)
const lastUpdated = ref<number | null>(null)

export function resetCameraHealth(label = '正在检测...') {
  status.value = 'unknown'
  message.value = label
  available.value = 0
  total.value = 0
  lastUpdated.value = Date.now()
}

export function setCameraHealth(okCount: number, totalCount: number) {
  available.value = okCount
  total.value = totalCount
  lastUpdated.value = Date.now()
  if (okCount > 0) {
    status.value = 'ok'
    message.value = `可用 ${okCount} 路`
  } else {
    status.value = 'error'
    message.value = '无可用摄像头'
  }
}

export const cameraHealth = {
  status,
  message,
  available,
  total,
  lastUpdated
}

