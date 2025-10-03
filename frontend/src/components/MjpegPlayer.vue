<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'

const props = defineProps<{ src: string | null | undefined }>()
const emit = defineEmits<{ (e: 'first-frame'): void; (e: 'error', detail?: any): void }>()

const imgRef = ref<HTMLImageElement | null>(null)
let baseSrc: string | null = null
let firstFrame = false
let retryTimer: number | null = null

function cleanupRetry() {
  if (retryTimer !== null) {
    window.clearTimeout(retryTimer)
    retryTimer = null
  }
}

function setSource(src: string | null) {
  cleanupRetry()
  baseSrc = src
  const img = imgRef.value
  firstFrame = false
  if (!img) return
  if (!src) {
    img.src = ''
    return
  }
  const finalUrl = src + (src.includes('?') ? '&' : '?') + 'cb=' + Date.now()
  img.src = finalUrl
}

function handleLoad() {
  if (!firstFrame) {
    firstFrame = true
    emit('first-frame')
  }
}

function scheduleRetry(detail: any) {
  cleanupRetry()
  if (!baseSrc) return
  retryTimer = window.setTimeout(() => {
    setSource(baseSrc)
  }, 1000)
  emit('error', detail)
}

watch(() => props.src ?? null, (val) => {
  setSource(val)
}, { immediate: true })

onBeforeUnmount(() => {
  cleanupRetry()
  const img = imgRef.value
  if (img) {
    img.src = ''
  }
})
</script>

<template>
  <img
    ref="imgRef"
    class="mjpeg-player"
    alt="live stream"
    @load="handleLoad"
    @error="scheduleRetry"
  />
</template>

<style scoped>
.mjpeg-player {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
}
</style>
