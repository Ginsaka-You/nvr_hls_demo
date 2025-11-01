import { ref } from 'vue'

const imsiUpdateToken = ref(0)
let stream: EventSource | null = null
let retryTimer: number | null = null

function scheduleReconnect(delay = 3000) {
  if (retryTimer) {
    window.clearTimeout(retryTimer)
  }
  retryTimer = window.setTimeout(() => {
    retryTimer = null
    openStream()
  }, delay)
}

function openStream() {
  try {
    stream = new EventSource('/api/imsi/subscribe')
    stream.onmessage = (ev) => {
      try {
        const data = JSON.parse((ev as MessageEvent).data)
        if (data && data.type === 'imsi') {
          imsiUpdateToken.value = Date.now()
        }
      } catch (err) {
        console.debug('IMSI SSE parse error', err)
      }
    }
    stream.onerror = () => {
      if (stream) {
        try { stream.close() } catch (err) { console.debug('IMSI SSE close error', err) }
      }
      stream = null
      scheduleReconnect()
    }
  } catch (err) {
    console.debug('Failed to open IMSI SSE stream', err)
    scheduleReconnect()
  }
}

export function connectImsiUpdates() {
  if (stream || retryTimer) {
    return
  }
  openStream()
}

export { imsiUpdateToken }

