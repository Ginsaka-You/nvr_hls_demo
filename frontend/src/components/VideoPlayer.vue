<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, defineProps, nextTick } from 'vue'
// @ts-ignore
import Hls from 'hls.js'

const props = defineProps<{ src: string }>()

const videoRef = ref<HTMLVideoElement|null>(null)
let hls: any
let retryTimer: any = null
let retries = 0
const maxRetries = 30
let lastSrc: string | null = null

function setupHls(video: HTMLVideoElement, src: string) {
  if (hls) { try { hls.destroy() } catch (_) {} hls = null }
  // Prefer Hls.js whenever supported (more controllable, visible in XHR)
  if (Hls.isSupported()) {
    console.log('[VideoPlayer] Using hls.js for', src)
    if (hls) { try { hls.destroy() } catch (_) {} hls = null }
    retries = 0
    hls = new Hls({
      // Stay close to live edge and limit buffer to reduce lag
      lowLatencyMode: false,
      // Use count-based live edge control (do not mix with duration-based)
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
      // Cache-bust to avoid any intermediary caching of the live manifest
      const cacheBusted = src + (src.includes('?') ? '&' : '?') + 'cb=' + Date.now()
      hls.loadSource(cacheBusted)
    })
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      void video.play().catch(err => console.error('Video play error:', err))
    })
    hls.on(Hls.Events.ERROR, (_e: any, data: any) => {
      console.error('HLS error:', data)
      const fatal = data?.fatal
      if (data?.type === Hls.ErrorTypes.NETWORK_ERROR && (data?.details === Hls.ErrorDetails.MANIFEST_LOAD_ERROR || data?.details === Hls.ErrorDetails.MANIFEST_LOAD_TIMEOUT)) {
        if (retries < maxRetries) {
          const delay = Math.min(2000, 300 + retries * 200)
          clearTimeout(retryTimer)
          retryTimer = setTimeout(() => {
            try { hls.loadSource(src); hls.startLoad() } catch (_) {}
          }, delay)
          retries++
        }
      } else if (fatal && data?.type === Hls.ErrorTypes.MEDIA_ERROR) {
        try { hls.recoverMediaError() } catch (err) { console.error('recoverMediaError failed', err) }
      } else if (fatal) {
        try { hls.destroy() } catch (_) {}
        hls = null
      }
    })
  } else {
    // Fallback: native HLS (Safari/iOS)
    console.log('[VideoPlayer] Using native HLS for', src)
    if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = src
      void video.play().catch(err => console.error('Video play error:', err))
    } else {
      console.error('HLS not supported in this browser')
    }
  }
}

async function play(src: string) {
  lastSrc = src
  if (!src) return
  await nextTick()
  const video = videoRef.value
  if (!video) return // wait for mount
  setupHls(video, src)
}

watch(() => props.src, (val) => {
  if (val) { void play(val) }
})

onMounted(() => {
  if (props.src) { void play(props.src) }
})

onBeforeUnmount(() => {
  if (hls) { try { hls.destroy() } catch (_) {} }
  if (retryTimer) { try { clearTimeout(retryTimer) } catch (_) {} retryTimer = null }
})
</script>

<template>
  <video ref="videoRef" controls autoplay playsinline style="width:100%;max-width:960px;height:auto;background:#000"></video>
</template>
