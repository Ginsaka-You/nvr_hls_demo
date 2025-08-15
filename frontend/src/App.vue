<script setup lang="ts">
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import VideoPlayer from './components/VideoPlayer.vue'

const streamId = ref('cam402')
const nvrUser = ref('admin')
const nvrPass = ref('00000000a')
const nvrHost = ref('192.168.50.76')
const status = ref<any>(null)
const hlsUrl = ref<string>('')

async function start() {
  // Build RTSP URL: derive channel from streamId digits, fallback 401
  const ch = (streamId.value.match(/\d+/)?.[0]) || '401'
  const rtsp = `rtsp://${nvrUser.value}:${encodeURIComponent(nvrPass.value)}@${nvrHost.value}:554/Streaming/Channels/${ch}`
  try {
    const resp = await fetch(`/api/streams/${streamId.value}/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `rtspUrl=${encodeURIComponent(rtsp)}`
    })

    if (!resp.ok) {
      const text = await resp.text()
      throw new Error(`API ${resp.status}: ${text}`)
    }

    // Prefer API-provided path if available; fallback to conventional path
    let data: any = {}
    try { data = await resp.json() } catch (_) {}

    // In dev, we proxy /streams to nginx, so a relative URL works
    const path = (data && data.hls) ? data.hls : `/streams/${streamId.value}/index.m3u8`
    hlsUrl.value = path.startsWith('/') ? path : `/${path}`
    message.success('启动命令已发送')
  } catch (err: any) {
    console.error(err)
    message.error('启动失败：' + (err?.message || err))
  }
  await refresh()
}

async function stop() {
  try {
    await fetch(`/api/streams/${streamId.value}`, { method: 'DELETE' })
    message.success('已停止')
  } catch (err: any) {
    console.error(err)
    message.error('停止失败：' + (err?.message || err))
  } finally {
    await refresh()
  }
}

async function refresh() {
  try {
    const resp = await fetch(`/api/streams/${streamId.value}/status`)
    if (!resp.ok) throw new Error(`API ${resp.status}`)
    status.value = await resp.json()
    // If backend reports hls path or running state, bind player URL automatically
    const path = (status.value && status.value.hls) ? status.value.hls : `/streams/${streamId.value}/index.m3u8`
    if (status.value && (status.value.hls || status.value.running || status.value.state === 'RUNNING')) {
      hlsUrl.value = path.startsWith('/') ? path : `/${path}`
    }
  } catch (err: any) {
    console.error(err)
  }
}
</script>

<template>
  <a-layout style="min-height:100vh;padding:24px">
    <a-typography-title :level="3">Hikvision NVR → HLS 播放（401 H.265 转 H.264）</a-typography-title>

    <a-space direction="vertical" style="width:100%">
      <a-form layout="inline">
        <a-form-item label="Stream ID"><a-input v-model:value="streamId" style="width:140px" /></a-form-item>
        <a-form-item label="NVR Host"><a-input v-model:value="nvrHost" style="width:160px" /></a-form-item>
        <a-form-item label="NVR User"><a-input v-model:value="nvrUser" style="width:120px" /></a-form-item>
        <a-form-item label="NVR Pass"><a-input-password v-model:value="nvrPass" style="width:160px" /></a-form-item>
        <a-form-item>
          <a-button type="primary" @click="start">启动</a-button>
        </a-form-item>
        <a-form-item>
          <a-button danger @click="stop">停止</a-button>
        </a-form-item>
        <a-form-item>
          <a-button @click="refresh">刷新状态</a-button>
        </a-form-item>
      </a-form>

      <div v-if="hlsUrl">
        <a-alert type="info" :message="'HLS: ' + hlsUrl" show-icon />
      </div>

      <VideoPlayer v-if="hlsUrl" :src="hlsUrl" />
    </a-space>
  </a-layout>
</template>

<style scoped>
</style>
