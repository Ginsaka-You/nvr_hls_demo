<script setup lang="ts">
import { ref, computed } from 'vue'
import { nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort, portCount, detectMain, detectSub, streamMode, hlsOrigin, webrtcServer, webrtcOptions, webrtcPreferCodec, channelOverrides, audioPass, audioId, audioHttpPort, radarHost, radarCtrlPort, radarDataPort, radarUseTcp, imsiFtpHost, imsiFtpPort, imsiFtpUser, imsiFtpPass, imsiSyncInterval, imsiSyncBatchSize, imsiFilenameTemplate, imsiLineTemplate, dbType, dbHost, dbPort, dbName, dbUser, dbPass } from '@/store/config'
import { message, Modal } from 'ant-design-vue'

const sec = ref<'multicam'|'alarm'|'imsi'|'radar'|'seismic'|'drone'|'database'>('multicam')

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

const radarProtocolDisplay = computed(() => (radarUseTcp.value ? 'TCP' : 'UDP'))
const radarTestHint = computed(() => (
  radarUseTcp.value
    ? '测试会尝试通过 TCP 握手（版本请求）验证雷达端口连通性。'
    : '测试会通过 UDP 发送启动指令并等待数据帧，验证雷达是否按期返回。'
))

const imsiTesting = ref(false)
const imsiTestResult = ref<any | null>(null)
const imsiTestError = ref<string | null>(null)

const imsiDetailLabels: Record<string, string> = {
  host: 'FTP IP',
  port: 'FTP 端口',
  user: 'FTP 用户',
  pass: 'FTP 密码',
  connectReplyCode: '连接回复码',
  loginReplyCode: '登录回复码',
  workingDirectory: '当前目录',
  passive: '被动模式',
  fileType: '文件类型'
}

const imsiTestTimestampDisplay = computed(() => {
  const ts = imsiTestResult.value?.timestamp
  if (!ts) return '—'
  const d = new Date(ts)
  return Number.isNaN(d.getTime()) ? ts : d.toLocaleString()
})

function formatImsiDetailValue(value: unknown): string {
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (value === null || value === undefined) return ''
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

const imsiTestDetailsList = computed(() => {
  const details = imsiTestResult.value?.details
  if (!details || typeof details !== 'object') return []
  return Object.entries(details as Record<string, unknown>).map(([key, value]) => ({
    key,
    label: imsiDetailLabels[key] ?? key,
    value: formatImsiDetailValue(value)
  }))
})

async function testRadar() {
  const host = (radarHost.value || '').trim()
  if (!host) {
    message.error('请先填写雷达 IP 地址')
    return
  }
  const ctrlPort = Number(radarCtrlPort.value) || 20000
  const dataPort = Number(radarDataPort.value) || ctrlPort
  const useTcp = !!radarUseTcp.value
  const ports = Array.from(new Set([ctrlPort, dataPort].filter((p): p is number => Number.isInteger(p) && p > 0 && p <= 65535)))
  radarTesting.value = true
  radarError.value = null
  radarAttempts.value = []
  radarTimestamp.value = null
  try {
    const resp = await fetch('/api/radar/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        host,
        controlPort: ctrlPort,
        dataPort,
        ports,
        useTcp,
        timeoutMs: 4000
      })
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
      message.success(`雷达连接测试成功（协议：${radarProtocolDisplay.value}）`)
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

async function testImsiFtp() {
  const host = (imsiFtpHost.value || '').trim()
  const user = (imsiFtpUser.value || '').trim()
  const pass = (imsiFtpPass.value || '').trim()
  if (!host || !user || !pass) {
    message.error('请先填写 FTP IP、用户名与密码')
    return
  }
  const port = Number(imsiFtpPort.value) || 21
  if (port <= 0 || port > 65535) {
    message.error('请填写有效的 FTP 端口')
    return
  }
  imsiTesting.value = true
  imsiTestError.value = null
  imsiTestResult.value = null
  try {
    const resp = await fetch('/api/imsi/test-ftp', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        host,
        port,
        user,
        pass,
        timeoutMs: 6000,
        intervalSeconds: imsiSyncInterval.value,
        batchSize: imsiSyncBatchSize.value,
        filenameTemplate: imsiFilenameTemplate.value,
        lineTemplate: imsiLineTemplate.value
      })
    })
    if (!resp.ok) {
      throw new Error(`请求失败 (${resp.status})`)
    }
    const data: any = await resp.json()
    imsiTestResult.value = data
    if (data?.ok) {
      imsiTestError.value = null
      message.success('FTP 连接测试成功')
    } else {
      imsiTestError.value = null
      message.error(data?.message || 'FTP 连接失败')
    }
  } catch (e: any) {
    const msg = e?.message || String(e)
    imsiTestError.value = msg
    message.error('测试失败：' + msg)
  } finally {
    imsiTesting.value = false
  }
}

// 清除缓存（删除 HLS 清单与分片）
const clearTargetId = ref<string>('')
function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  const value = bytes / Math.pow(1024, index)
  return `${value.toFixed(index >= 2 ? 2 : 1)} ${units[index]}`
}

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
          const deleted = Number(data?.deleted || 0)
          const webrtcDirs = Array.isArray(data?.webrtcDirs) ? data.webrtcDirs.length : 0
          const bytesFreed = Number(data?.bytesFreed || 0)
          Modal.success({
            title: '缓存清理完成',
            content: `释放磁盘空间：${formatBytes(bytesFreed)}（HLS 文件 ${deleted} 个，WebRTC 缓存目录 ${webrtcDirs} 个）`,
            okText: '好的'
          })
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
            <a-menu-item key="database" @click="sec='database'">数据库</a-menu-item>
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
              <a-form-item label="通道覆盖">
                <a-input v-model:value="channelOverrides" style="width:320px" placeholder="例如 701/702,801/802（留空=自动检测）" />
              </a-form-item>
              <a-form-item :wrapper-col="{ offset: 5 }">
                <a-alert type="info" show-icon message="格式：主码流/子码流；多个摄像头用逗号分隔（示例：701/702,801/802）。留空则按照端口自动检测。" />
              </a-form-item>
              <a-form-item label="检测类型">
                <a-checkbox v-model:checked="detectSub">子(02)</a-checkbox>
                <a-checkbox v-model:checked="detectMain" style="margin-left:12px">主(01)</a-checkbox>
              </a-form-item>
              <a-form-item label="播放模式">
                <a-radio-group v-model:value="streamMode">
                  <a-radio value="hls">HLS（兼容）</a-radio>
                  <a-radio value="webrtc">WebRTC（低延迟）</a-radio>
                </a-radio-group>
              </a-form-item>
              <a-form-item v-if="streamMode==='hls'" label="HLS 域名">
                <a-input v-model:value="hlsOrigin" style="width:320px" placeholder="可选，例如 http://127.0.0.1:8080" />
              </a-form-item>
              <a-form-item v-else label="WebRTC 服务">
                <a-input v-model:value="webrtcServer" style="width:320px" placeholder="http://127.0.0.1:8000" />
              </a-form-item>
              <a-form-item v-if="streamMode==='webrtc'" label="连接参数">
                <a-input v-model:value="webrtcOptions" style="width:320px" placeholder="rtptransport=tcp&timeout=60" />
              </a-form-item>
              <a-form-item v-if="streamMode==='webrtc'" label="优先编码">
                <a-input v-model:value="webrtcPreferCodec" style="width:320px" placeholder="video/H264" />
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

          <template v-else-if="sec==='imsi'">
            <a-typography-title :level="5" style="color: var(--text-color)">IMSI · FTP 数据同步</a-typography-title>
            <a-form layout="horizontal" :label-col="{ span: 6 }" :wrapper-col="{ span: 16 }">
              <a-form-item label="FTP IP">
                <a-input v-model:value="imsiFtpHost" style="width:220px" placeholder="例如 47.98.168.56" />
              </a-form-item>
              <a-form-item label="FTP 端口">
                <a-input-number v-model:value="imsiFtpPort" :min="1" :max="65535" style="width:220px" />
              </a-form-item>
              <a-form-item label="FTP 用户">
                <a-input v-model:value="imsiFtpUser" style="width:220px" placeholder="例如 ftpuser" />
              </a-form-item>
              <a-form-item label="FTP 密码">
                <a-input-password v-model:value="imsiFtpPass" style="width:220px" placeholder="例如 ftpPass@47" />
              </a-form-item>
              <a-form-item label="同步间隔 (秒)">
                <a-input-number v-model:value="imsiSyncInterval" :min="10" :max="86400" style="width:220px" />
              </a-form-item>
              <a-form-item label="同步数量">
                <a-input-number v-model:value="imsiSyncBatchSize" :min="1" :max="20000" style="width:220px" />
              </a-form-item>
              <a-form-item label="文件名模板">
                <a-input v-model:value="imsiFilenameTemplate" style="width:360px" placeholder="CTC_{deviceId}_{dateyymmdd}_{timestamp}.txt" />
              </a-form-item>
              <a-form-item label="数据行模板">
                <a-textarea v-model:value="imsiLineTemplate" style="width:360px" :auto-size="{ minRows: 2, maxRows: 4 }" />
              </a-form-item>
              <a-form-item :wrapper-col="{ offset: 6 }">
                <a-space>
                  <a-button type="primary" @click="saveAndNotify">保存</a-button>
                  <a-button @click="testImsiFtp" :loading="imsiTesting" :disabled="imsiTesting">测试连接</a-button>
                </a-space>
              </a-form-item>
            </a-form>
            <a-spin :spinning="imsiTesting">
              <div style="margin-top:12px;">
                <a-alert
                  v-if="imsiTestError"
                  type="error"
                  show-icon
                  :message="imsiTestError"
                />
                <template v-else-if="imsiTestResult">
                  <a-alert
                    :type="imsiTestResult?.ok ? 'success' : 'warning'"
                    show-icon
                    :message="imsiTestResult?.message || (imsiTestResult?.ok ? 'FTP 连接成功' : 'FTP 连接失败')"
                  />
                  <div style="margin-top:8px; color: rgba(0,0,0,0.65);">
                    <span>上次测试时间：{{ imsiTestTimestampDisplay }}</span>
                    <span v-if="imsiTestResult?.elapsedMs" style="margin-left:16px;">耗时：{{ imsiTestResult.elapsedMs }} ms</span>
                  </div>
                  <a-descriptions
                    v-if="imsiTestDetailsList.length"
                    size="small"
                    bordered
                    :column="1"
                    style="margin-top:8px;"
                  >
                    <a-descriptions-item v-for="item in imsiTestDetailsList" :key="item.key" :label="item.label">
                      {{ item.value }}
                    </a-descriptions-item>
                  </a-descriptions>
                </template>
                <a-alert
                  v-else
                  type="info"
                  show-icon
                  message="尚未进行 FTP 测试。点击“测试连接”验证配置。"
                />
              </div>
            </a-spin>
            <a-alert
              type="info"
              show-icon
              message="说明：模板字段支持 {deviceId}/{imsi}/{operator}/{area}/{rptTimeyymmdd}/{rptTimehhmmss}/{timestamp} 等占位符；数据行中的 \\t 按照实际制表符导出。"
              style="margin-top:12px;"
            />
          </template>

          <template v-else-if="sec==='radar'">
            <a-typography-title :level="5" style="color: var(--text-color)">雷达 · 网络连接</a-typography-title>
            <a-form layout="horizontal" :label-col="{ span: 5 }" :wrapper-col="{ span: 16 }">
              <a-form-item label="雷达 IP">
                <a-input v-model:value="radarHost" style="width:220px" placeholder="例如 192.168.2.40" />
              </a-form-item>
              <a-form-item label="指令端口">
                <a-input-number v-model:value="radarCtrlPort" :min="1" :max="65535" style="width:220px" />
              </a-form-item>
              <a-form-item label="数据端口">
                <a-input-number v-model:value="radarDataPort" :min="1" :max="65535" style="width:220px" />
              </a-form-item>
              <a-form-item label="传输协议">
                <a-radio-group v-model:value="radarUseTcp">
                  <a-radio :value="false">UDP</a-radio>
                  <a-radio :value="true">TCP</a-radio>
                </a-radio-group>
              </a-form-item>
              <a-form-item :wrapper-col="{ offset: 5 }">
                <a-space>
                  <a-button type="primary" @click="saveAndNotify">保存</a-button>
                  <a-button @click="testRadar" :loading="radarTesting" :disabled="radarTesting">测试连接</a-button>
                  <span style="color: rgba(0,0,0,0.45);">当前协议：{{ radarProtocolDisplay }}</span>
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
            <a-alert
              type="info"
              show-icon
              :message="`说明：${radarTestHint}（当前协议：${radarProtocolDisplay}）`"
              style="margin-top:12px;"
            />
          </template>

          <template v-else-if="sec==='database'">
            <a-typography-title :level="5" style="color: var(--text-color)">数据库连接</a-typography-title>
            <a-form layout="horizontal" :label-col="{ span: 5 }" :wrapper-col="{ span: 16 }">
              <a-form-item label="数据库类型">
                <a-select v-model:value="dbType" style="width:220px">
                  <a-select-option value="mysql">MySQL / MariaDB</a-select-option>
                  <a-select-option value="postgres">PostgreSQL</a-select-option>
                  <a-select-option value="sqlserver">SQL Server</a-select-option>
                </a-select>
              </a-form-item>
              <a-form-item label="主机">
                <a-input v-model:value="dbHost" style="width:220px" placeholder="例如 127.0.0.1" />
              </a-form-item>
              <a-form-item label="端口">
                <a-input-number v-model:value="dbPort" :min="1" :max="65535" style="width:220px" />
              </a-form-item>
              <a-form-item label="数据库名">
                <a-input v-model:value="dbName" style="width:220px" placeholder="例如 nvr" />
              </a-form-item>
              <a-form-item label="用户名">
                <a-input v-model:value="dbUser" style="width:220px" placeholder="例如 root" />
              </a-form-item>
              <a-form-item label="密码">
                <a-input-password v-model:value="dbPass" style="width:220px" placeholder="留空则按数据库默认" />
              </a-form-item>
              <a-form-item :wrapper-col="{ offset: 5 }">
                <a-button type="primary" @click="saveAndNotify">保存</a-button>
              </a-form-item>
            </a-form>
            <a-alert type="info" show-icon message="说明：此处仅保存连接信息，服务端可使用这些参数初始化或更新数据库连接池。" />
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
