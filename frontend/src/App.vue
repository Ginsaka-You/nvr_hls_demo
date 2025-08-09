<script setup lang="ts">
import { ref } from 'vue'
import VideoPlayer from './components/VideoPlayer.vue'

const streamId = ref('cam401')
const nvrUser = ref('admin')
const nvrPass = ref('密码')
const nvrHost = ref('192.168.50.76')
const status = ref<any>(null)
const hlsUrl = ref<string>('')

async function start() {
  const rtsp = `rtsp://${nvrUser.value}:${encodeURIComponent(nvrPass.value)}@${nvrHost.value}:554/Streaming/Channels/401`
  const resp = await fetch(`/api/streams/${streamId.value}/start`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `rtspUrl=${encodeURIComponent(rtsp)}`
  })
  const data = await resp.json()
  const nginxOrigin = 'http://127.0.0.1'; // 若在别的设备上看，换成你的局域网IP
hlsUrl.value = nginxOrigin + '/streams/cam401/index.m3u8';
  await refresh()
}

async function stop() {
  await fetch(`/api/streams/${streamId.value}`, { method: 'DELETE' })
  await refresh()
}

async function refresh() {
  const resp = await fetch(`/api/streams/${streamId.value}/status`)
  status.value = await resp.json()
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
