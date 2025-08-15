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
const loading = ref(false)

async function start() {
  // Build RTSP URL: derive channel from streamId digits, fallback 401
  const ch = (streamId.value.match(/\d+/)?.[0]) || '401'
  const rtsp = `rtsp://${nvrUser.value}:${encodeURIComponent(nvrPass.value)}@${nvrHost.value}:554/Streaming/Channels/${ch}`
  try {
    loading.value = true
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

    // Candidate HLS path
    const path = (data && data.hls) ? data.hls : `/streams/${streamId.value}/index.m3u8`
    const normalized = path.startsWith('/') ? path : `/${path}`

    // Wait until backend reports playlist exists and segments are present
    const ok = await waitUntilReady(streamId.value, 15000)
    if (ok) {
      hlsUrl.value = normalized
      message.success('启动成功，开始播放')
    } else {
      // Fallback: still set src; VideoPlayer 会重试清单加载
      hlsUrl.value = normalized
      message.warning('已发送启动，但清单未就绪，尝试播放中…')
    }
  } catch (err: any) {
    console.error(err)
    message.error('启动失败：' + (err?.message || err))
  }
  await refresh()
  loading.value = false
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

async function waitUntilReady(id: string, timeoutMs = 10000) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const resp = await fetch(`/api/streams/${id}/status`)
      if (resp.ok) {
        const s = await resp.json()
        const probe = s?.probe
        if (probe && probe.m3u8Exists && (probe.tsCount ?? 0) > 0) return true
      }
    } catch (_) {}
    await new Promise(r => setTimeout(r, 800))
  }
  return false
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
          <a-button type="primary" :loading="loading" @click="start">启动</a-button>
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
