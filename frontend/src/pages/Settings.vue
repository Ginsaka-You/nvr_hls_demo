<script setup lang="ts">
import { ref, computed } from 'vue'
import { nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort, portCount, detectMain, detectSub, audioPass, audioId, audioHttpPort, radarHost, radarCtrlPort, radarDataPort, radarUseTcp } from '@/store/config'
import { message, Modal } from 'ant-design-vue'

const sec = ref<'multicam'|'alarm'|'imsi'|'radar'|'seismic'|'drone'>('multicam')

async function testAudio() {
  try {
    const host = (nvrHost.value || '').trim()
    const user = (nvrUser.value || '').trim()
    const pass = (audioPass.value || nvrPass.value || '').trim()
    const id = audioId.value
    if (!host || !user || !pass || !id) {
      message.error('请先填写 NVR Host、用户名、音频告警密码与默认ID')
      return
    }
    const p = new URLSearchParams({ host, user, pass, scheme: nvrScheme.value, id: String(id) })
    if (audioHttpPort.value) p.set('httpPort', String(audioHttpPort.value))
    const resp = await fetch('/api/nvr/ipc/audioAlarm/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: p.toString()
    })
    const data: any = await resp.json().catch(() => ({}))
    if (data?.ok) {
      message.success(`已触发，接口: ${data.used || '未知'}`)
    } else {
      const status = data?.status || data?.attempts?.[data?.attempts?.length-1]?.status || '请求失败'
      message.error(`触发失败：${status}`)
    }
  } catch (e: any) {
    message.error('触发失败：' + (e?.message || e))
  }
}

function saveAndNotify() {
  message.success('设置已保存，相关页面将自动生效')
}

type RadarTestAttempt = {
  port: number
  ok: boolean
  message: string
  elapsedMs: number
}

const radarTesting = ref(false)
const radarAttempts = ref<RadarTestAttempt[]>([])
const radarTimestamp = ref<string | null>(null)
const radarError = ref<string | null>(null)

const radarTableData = computed(() => radarAttempts.value.map((a, idx) => ({
  key: idx,
  port: a.port,
  result: a.ok ? '成功' : '失败',
  elapsed: a.elapsedMs,
  message: a.message
})))

const radarTimestampDisplay = computed(() => {
  if (!radarTimestamp.value) return '—'
  const d = new Date(radarTimestamp.value)
  return Number.isNaN(d.getTime()) ? radarTimestamp.value : d.toLocaleString()
})

const radarColumns = [
  { title: '端口', dataIndex: 'port', key: 'port', width: 120 },
  { title: '结果', dataIndex: 'result', key: 'result', width: 120 },
  { title: '耗时 (ms)', dataIndex: 'elapsed', key: 'elapsed', width: 120 },
  { title: '说明', dataIndex: 'message', key: 'message' }
]

async function testRadar() {
  const host = (radarHost.value || '').trim()
  if (!host) {
    message.error('请先填写雷达 IP 地址')
    return
  }
  radarTesting.value = true
  radarError.value = null
  radarAttempts.value = []
  radarTimestamp.value = null
  try {
    const ports = [radarCtrlPort.value, radarDataPort.value]
      .filter((p): p is number => typeof p === 'number' && Number.isFinite(p) && p > 0 && p <= 65535)
    const resp = await fetch('/api/radar/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ host, ports })
    })
    if (!resp.ok) {
      throw new Error(`请求失败 (${resp.status})`)
    }
    const data: any = await resp.json()
    radarTimestamp.value = data?.timestamp ?? null
    if (Array.isArray(data?.attempts)) {
      radarAttempts.value = data.attempts.map((it: any) => ({
        port: Number(it.port),
        ok: Boolean(it.ok),
        message: String(it.message ?? ''),
        elapsedMs: Number(it.elapsedMs ?? 0)
      }))
    }
    if (data?.error) {
      radarError.value = data.error
      message.error(data.error)
      return
    }
    if (data?.ok) {
      message.success('雷达连接测试成功')
    } else {
      const firstFail = radarAttempts.value.find(a => !a.ok)
      message.error(firstFail ? `雷达连接失败：${firstFail.message}` : '雷达连接失败')
    }
  } catch (e: any) {
    radarError.value = e?.message || String(e)
    message.error('测试失败：' + radarError.value)
  } finally {
    radarTesting.value = false
  }
}

// 清除缓存（删除 HLS 清单与分片）
const clearTargetId = ref<string>('')
async function clearHls() {
  const id = (clearTargetId.value || '').trim()
  const title = id ? `确定删除 ${id} 的 HLS 文件？` : '确定删除所有流的 HLS 文件？'
  Modal.confirm({
    title,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        const body = id ? `id=${encodeURIComponent(id)}` : ''
        const resp = await fetch('/api/hls/clear', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body
        })
        if (!resp.ok) throw new Error(`API ${resp.status}`)
        const data: any = await resp.json()
        if (data?.ok) {
          message.success(`已删除 ${data.deleted || 0} 个文件`)
        } else {
          message.error('删除失败：' + (data?.error || '未知错误'))
        }
      } catch (e: any) {
        message.error('删除失败：' + (e?.message || e))
      }
    }
  })
}
</script>

<template>
  <a-layout style="min-height:calc(100vh - 64px);">
    <a-layout-content style="padding:16px; color: var(--text-color);">
      <a-typography-title :level="4" style="color: var(--text-color)">系统设置</a-typography-title>

      <div style="display:grid; grid-template-columns: 240px 1fr; gap: 12px; align-items:start;">
        <!-- 左侧悬浮设置分类 -->
        <div style="background: var(--panel-bg); border:1px solid var(--panel-border); border-radius:8px; box-shadow:0 2px 10px rgba(0,0,0,0.25); overflow:hidden;">
          <a-menu mode="inline" theme="light" :selectedKeys="[sec]" :style="{borderRight:0}">
            <a-menu-item key="multicam" @click="sec='multicam'">多摄像头</a-menu-item>
            <a-menu-item key="alarm" @click="sec='alarm'">告警联动</a-menu-item>
            <a-menu-item key="imsi" @click="sec='imsi'">IMSI</a-menu-item>
            <a-menu-item key="radar" @click="sec='radar'">雷达</a-menu-item>
            <a-menu-item key="seismic" @click="sec='seismic'">震动</a-menu-item>
            <a-menu-item key="drone" @click="sec='drone'">无人机</a-menu-item>
          </a-menu>
        </div>

        <!-- 右侧设置面板 -->
        <div style="background: var(--panel-bg); border:1px solid var(--panel-border); border-radius:8px; box-shadow:0 2px 10px rgba(0,0,0,0.25); padding:12px;">
          <template v-if="sec==='multicam'">
            <a-typography-title :level="5" style="color: var(--text-color)">多摄像头 · 录像机与检测</a-typography-title>
            <a-form layout="horizontal" :label-col="{ span: 5 }" :wrapper-col="{ span: 16 }">
              <a-form-item label="协议">
                <a-radio-group v-model:value="nvrScheme">
                  <a-radio value="http">HTTP</a-radio>
                  <a-radio value="https">HTTPS</a-radio>
                </a-radio-group>
              </a-form-item>
              <a-form-item label="NVR Host"><a-input v-model:value="nvrHost" style="width:220px" /></a-form-item>
              <a-form-item label="用户名"><a-input v-model:value="nvrUser" style="width:220px" /></a-form-item>
              <a-form-item label="密码"><a-input-password v-model:value="nvrPass" style="width:220px" /></a-form-item>
              <a-form-item label="端口"><a-input-number v-model:value="nvrHttpPort" :min="1" :max="65535" style="width:220px" placeholder="留空=默认(80/443)" /></a-form-item>
              <a-form-item label="端口数"><a-input-number v-model:value="portCount" :min="1" :max="32" style="width:220px" /></a-form-item>
              <a-form-item label="检测类型">
                <a-checkbox v-model:checked="detectSub">子(02)</a-checkbox>
                <a-checkbox v-model:checked="detectMain" style="margin-left:12px">主(01)</a-checkbox>
              </a-form-item>
              <a-form-item :wrapper-col="{ offset: 5 }">
                <a-button type="primary" @click="saveAndNotify">保存</a-button>
              </a-form-item>
            </a-form>
            <a-alert type="info" show-icon message="说明：保存后，多摄像头页面会按新参数自动检测并播放。" />
          </template>

          <template v-else-if="sec==='alarm'">
            <a-typography-title :level="5" style="color: var(--text-color)">告警联动 · 摄像头声音</a-typography-title>
            <a-form layout="horizontal" :label-col="{ span: 5 }" :wrapper-col="{ span: 16 }">
              <a-form-item label="音频告警密码">
                <a-input-password v-model:value="audioPass" style="width:220px" placeholder="与NVR密码不同时在此设置" />
              </a-form-item>
              <a-form-item label="摄像头端口">
                <a-input-number v-model:value="audioHttpPort" :min="1" :max="65535" style="width:220px" />
              </a-form-item>
              <a-form-item label="默认ID">
                <a-input-number v-model:value="audioId" :min="1" :max="128" style="width:220px" />
              </a-form-item>
              <a-form-item :wrapper-col="{ offset: 5 }">
                <a-space>
                  <a-button type="primary" @click="saveAndNotify">保存</a-button>
                  <a-button @click="testAudio">测试声音</a-button>
                </a-space>
              </a-form-item>
            </a-form>
            <a-alert type="info" show-icon :message="'说明：当告警到达时，系统将调用 /api/nvr/ipc/audioAlarm/test 以【默认ID】触发摄像头声音；此处的“摄像头端口”仅用于该触发请求，不影响NVR接口。'" />
          </template>

          <template v-else-if="sec==='radar'">
            <a-typography-title :level="5" style="color: var(--text-color)">雷达 · 网络连接</a-typography-title>
            <a-form layout="horizontal" :label-col="{ span: 5 }" :wrapper-col="{ span: 16 }">
              <a-form-item label="雷达 IP">
                <a-input v-model:value="radarHost" style="width:220px" placeholder="例如 192.168.2.100" />
              </a-form-item>
              <a-form-item label="指令端口">
                <a-input-number v-model:value="radarCtrlPort" :min="1" :max="65535" style="width:220px" />
              </a-form-item>
              <a-form-item label="数据端口">
                <a-input-number v-model:value="radarDataPort" :min="1" :max="65535" style="width:220px" />
              </a-form-item>
              <a-form-item label="数据使用 TCP">
                <a-switch v-model:checked="radarUseTcp" />
              </a-form-item>
              <a-form-item :wrapper-col="{ offset: 5 }">
                <a-space>
                  <a-button type="primary" @click="saveAndNotify">保存</a-button>
                  <a-button @click="testRadar" :loading="radarTesting">测试连接</a-button>
                </a-space>
              </a-form-item>
            </a-form>
            <a-spin :spinning="radarTesting">
              <div v-if="radarTimestamp || radarAttempts.length" style="margin-top:12px;">
                <span style="color: rgba(0,0,0,0.65);">上次测试时间：{{ radarTimestampDisplay }}</span>
              </div>
              <a-alert v-if="radarError" type="error" :message="radarError" show-icon style="margin-top:12px;" />
              <a-table
                v-if="radarAttempts.length"
                size="small"
                bordered
                :columns="radarColumns"
                :data-source="radarTableData"
                :pagination="false"
                style="margin-top:12px;"
              />
              <a-alert
                v-else
                type="info"
                show-icon
                message="尚未进行连接测试。点击“测试连接”以验证雷达端口响应。"
                style="margin-top:12px;"
              />
            </a-spin>
            <a-alert type="info" show-icon message="说明：测试会对指定端口发起 TCP 握手以验证网络可达性，通常对应协议文档中的 20000/20001 端口。" style="margin-top:12px;" />
          </template>

          <template v-else>
            <div class="muted">该模块暂未提供可配置项。</div>
          </template>

          <a-divider />
          <a-typography-title :level="5" style="color: var(--text-color)">缓存与临时文件</a-typography-title>
          <a-form layout="horizontal" :label-col="{ span: 5 }" :wrapper-col="{ span: 16 }">
            <a-form-item label="Stream ID">
              <a-input v-model:value="clearTargetId" style="width:220px" placeholder="留空表示全部，如 cam402" />
            </a-form-item>
            <a-form-item :wrapper-col="{ offset: 5 }">
              <a-button danger @click="clearHls">清除缓存文件（删除 .m3u8 与 .ts）</a-button>
            </a-form-item>
          </a-form>
        </div>
      </div>
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
</style>
