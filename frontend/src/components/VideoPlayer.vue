<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch, defineProps } from 'vue'
// @ts-ignore
import Hls from 'hls.js'

const props = defineProps<{ src: string }>()

const videoRef = ref<HTMLVideoElement|null>(null)
let hls: any

function play(src: string) {
  const video = videoRef.value!
  if (video.canPlayType('application/vnd.apple.mpegurl')) {
    video.src = src
    video.play()
  } else if (Hls.isSupported()) {
    if (hls) { hls.destroy() }
    hls = new Hls({ liveSyncDuration: 2, liveMaxLatencyDuration: 6 })
    hls.loadSource(src)
    hls.attachMedia(video)
    hls.on(Hls.Events.MANIFEST_PARSED, () => video.play())
  } else {
    console.error('HLS not supported')
  }
}

watch(() => props.src, (val) => { if (val) play(val) }, { immediate: true })

onBeforeUnmount(() => {
  if (hls) { hls.destroy() }
})
</script>

<template>
  <video ref="videoRef" controls autoplay playsinline style="width:100%;max-width:960px;height:auto;background:#000"></video>
</template>
