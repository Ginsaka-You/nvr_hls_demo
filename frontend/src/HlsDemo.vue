<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
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

    let data: any = {}
    try { data = await resp.json() } catch (_) {}
    const path = (data && data.hls) ? data.hls : `/streams/${streamId.value}/index.m3u8`
    const normalized = path.startsWith('/') ? path : `/${path}`

    const ok = await waitUntilReady(streamId.value, 15000)
    hlsUrl.value = normalized
    ok ? message.success('启动成功，开始播放') : message.warning('已发送启动，但清单未就绪，尝试播放中…')
  } catch (err: any) {
    console.error(err)
    message.error('启动失败：' + (err?.message || err))
  }
  loading.value = false
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

// 自动启动：进入页面后即启动后端并播放
onMounted(() => {
  // 小延迟保证初始表单值已就绪
  setTimeout(() => { void start() }, 50)
})

// 若用户修改了连接参数/通道，自动重新启动
let autoTimer: any = null
watch([streamId, nvrUser, nvrPass, nvrHost], () => {
  if (autoTimer) clearTimeout(autoTimer)
  autoTimer = setTimeout(() => { void start() }, 500)
})
</script>

<template>
  <a-layout style="min-height:calc(100vh - 64px);background:#0b1220;">
    <a-layout-content style="padding:16px;background:#0b1220; color:#cbd5e1;">
      <a-typography-title :level="4" style="color:#cbd5e1">Hikvision NVR → HLS 播放</a-typography-title>

      <a-space direction="vertical" style="width:100%">
        <a-form layout="inline">
          <a-form-item label="Stream ID"><a-input v-model:value="streamId" style="width:140px" /></a-form-item>
          <a-form-item label="NVR Host"><a-input v-model:value="nvrHost" style="width:160px" /></a-form-item>
          <a-form-item label="NVR User"><a-input v-model:value="nvrUser" style="width:120px" /></a-form-item>
          <a-form-item label="NVR Pass"><a-input-password v-model:value="nvrPass" style="width:160px" /></a-form-item>
        </a-form>

        <div v-if="hlsUrl">
          <a-alert type="info" :message="'HLS: ' + hlsUrl" show-icon />
        </div>

        <VideoPlayer v-if="hlsUrl" :src="hlsUrl" />
      </a-space>
    </a-layout-content>
    <a-layout-footer style="background:#0f172a; border-top:1px solid #1e293b; padding:8px;">
      <div style="display:flex; align-items:center; gap:12px; color:#cbd5e1;">
        <div style="color:#93c5fd;">控制台</div>
        <a-button size="small">PTZ 上</a-button>
        <a-button size="small">PTZ 下</a-button>
        <a-button size="small">PTZ 左</a-button>
        <a-button size="small">PTZ 右</a-button>
        <a-divider type="vertical" />
        <div style="margin-left:auto; color:#64748b;">时间轴占位（事件密度条 / 回放）</div>
      </div>
    </a-layout-footer>
  </a-layout>
</template>

<style scoped>
.ant-typography { color: #e2e8f0; }
</style>
