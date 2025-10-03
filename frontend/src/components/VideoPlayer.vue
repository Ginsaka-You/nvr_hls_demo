<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, defineProps, defineEmits, nextTick } from 'vue'
// @ts-ignore
import Hls from 'hls.js'
import { StreamSource, isHlsSource, isWebRtcSource } from '@/types/stream'
import { WebRtcStreamerClient } from '@/lib/WebRtcStreamer'

const props = defineProps<{ source?: StreamSource | null }>()
const emit = defineEmits<{
  (e: 'loading'): void
  (e: 'connected'): void
  (e: 'failed', reason?: string): void
}>()

const videoRef = ref<HTMLVideoElement | null>(null)
let hls: any
let webrtc: WebRtcStreamerClient | null = null
let retryTimer: ReturnType<typeof setTimeout> | null = null
let retries = 0
const maxRetries = 30

async function cleanup() {
  if (retryTimer) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
  retries = 0
  if (hls) {
    try { hls.destroy() } catch (err) { console.warn('Hls destroy failed', err) }
    hls = null
  }
  if (webrtc) {
    await webrtc.disconnect()
    webrtc = null
  }
}

function setupHls(video: HTMLVideoElement, src: string) {
  if (hls) { try { hls.destroy() } catch (_) {} hls = null }
  if (Hls.isSupported()) {
    console.log('[VideoPlayer] Using hls.js for', src)
    retries = 0
    hls = new Hls({
      lowLatencyMode: false,
      liveSyncDurationCount: 1,
      liveMaxLatencyDurationCount: 3,
      maxBufferLength: 5,
      maxBufferSize: 5 * 1000 * 1000,
      maxBufferHole: 0.5,
      backBufferLength: 15,
      enableWorker: true
    })
    hls.attachMedia(video)
    hls.on(Hls.Events.MEDIA_ATTACHED, () => {
      const cacheBusted = src + (src.includes('?') ? '&' : '?') + 'cb=' + Date.now()
      hls.loadSource(cacheBusted)
    })
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      void video.play().then(() => emit('connected')).catch(err => {
        console.error('Video play error:', err)
        emit('failed', err?.message || String(err))
      })
    })
    hls.on(Hls.Events.ERROR, (_e: any, data: any) => {
      console.error('HLS error:', data)
      const fatal = data?.fatal
      if (data?.type === Hls.ErrorTypes.NETWORK_ERROR && (data?.details === Hls.ErrorDetails.MANIFEST_LOAD_ERROR || data?.details === Hls.ErrorDetails.MANIFEST_LOAD_TIMEOUT)) {
        if (retries < maxRetries) {
          const delay = Math.min(2000, 300 + retries * 200)
          if (retryTimer) clearTimeout(retryTimer)
          retryTimer = setTimeout(() => {
            try { hls.loadSource(src); hls.startLoad() } catch (err) { console.error('Retry HLS failed', err) }
          }, delay)
          retries++
        }
      } else if (fatal && data?.type === Hls.ErrorTypes.MEDIA_ERROR) {
        try { hls.recoverMediaError() } catch (err) { console.error('recoverMediaError failed', err) }
      } else if (fatal) {
        try { hls.destroy() } catch (_) {}
        hls = null
        emit('failed', data?.details || 'HLS fatal error')
      }
    })
  } else {
    console.log('[VideoPlayer] Using native HLS for', src)
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = src
      const onLoaded = () => {
        emit('connected')
        video.removeEventListener('loadeddata', onLoaded)
      }
      video.addEventListener('loadeddata', onLoaded)
      void video.play().catch(err => {
        console.error('Video play error:', err)
        emit('failed', err?.message || String(err))
      })
    } else {
      const reason = 'HLS not supported in this browser'
      console.error(reason)
      emit('failed', reason)
    }
  }
}

async function setupWebRtc(video: HTMLVideoElement, source: StreamSource) {
  if (!isWebRtcSource(source)) return
  try {
    video.muted = true
    webrtc = new WebRtcStreamerClient(video, source.server)
    webrtc.onConnected(() => emit('connected'))
    webrtc.onIceState((state) => {
      if (state === 'connected') {
        emit('connected')
      } else if (state === 'failed' || state === 'closed') {
        emit('failed', `ICE ${state}`)
      }
    })
    webrtc.onError(reason => emit('failed', reason))
    await webrtc.connect({
      videoUrl: source.url,
      audioUrl: source.audioUrl,
      options: source.options,
      preferredCodec: source.preferCodec
    })
  } catch (err) {
    console.error('[VideoPlayer] WebRTC connect failed', err)
    emit('failed', err instanceof Error ? err.message : String(err))
  }
}

async function play(source?: StreamSource | null) {
  await cleanup()
  if (!source) return
  emit('loading')
  await nextTick()
  const video = videoRef.value
  if (!video) return
  if (isHlsSource(source)) {
    setupHls(video, source.url)
  } else if (isWebRtcSource(source)) {
    await setupWebRtc(video, source)
  }
}

watch(() => props.source, (val) => {
  void play(val || undefined)
})

onMounted(() => {
  void play(props.source)
})

onBeforeUnmount(() => {
  void cleanup()
})
</script>

<template>
  <video ref="videoRef" controls autoplay playsinline style="width:100%;max-width:960px;height:auto;background:#000"></video>
</template>
