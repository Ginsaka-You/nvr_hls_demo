<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, defineProps, nextTick } from 'vue'
// @ts-ignore
import Hls from 'hls.js'

const props = defineProps<{ src: string }>()

const videoRef = ref<HTMLVideoElement|null>(null)
let hls: any
let lastSrc: string | null = null

function setupHls(video: HTMLVideoElement, src: string) {
  if (hls) { try { hls.destroy() } catch (_) {} hls = null }
  // Prefer Hls.js on non-Safari browsers to avoid false-positive native HLS support
  const isSafari = /^((?!chrome|android).)*safari/i.test(navigator.userAgent)
  if (!isSafari && Hls.isSupported()) {
    hls = new Hls({ liveSyncDuration: 2, liveMaxLatencyDuration: 6 })
    hls.attachMedia(video)
    hls.on(Hls.Events.MEDIA_ATTACHED, () => {
      hls.loadSource(src)
    })
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      void video.play().catch(err => console.error('Video play error:', err))
    })
    hls.on(Hls.Events.ERROR, (_e: any, data: any) => {
      console.error('HLS error:', data)
    })
  } else {
    // Safari/iOS: use native HLS
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
})
</script>

<template>
  <video ref="videoRef" controls autoplay playsinline style="width:100%;max-width:960px;height:auto;background:#000"></video>
</template>
