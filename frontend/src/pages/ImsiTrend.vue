<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { imsiFtpHost, imsiFtpUser, imsiFtpPass, imsiSyncInterval, imsiDeviceFilter } from '@/store/config'

type ImsiRecord = {
  deviceId: string
  imsi: string
  operator: string
  area: string
  rptDate: string
  rptTime: string
  sourceFile: string
  lineNumber: number
}

type TableRow = ImsiRecord & {
  key: string
  operatorLabel: string
  timestampDisplay: string
}

const loading = ref(false)
const error = ref<string | null>(null)
const records = ref<ImsiRecord[]>([])
const sourceFiles = ref<string[]>([])
const lastUpdated = ref<string | null>(null)
const metaMessage = ref<string | null>(null)
const elapsedMs = ref<number | null>(null)
const deviceFilter = ref<string>('')
const imsiFilter = ref<string>('')
const syncing = ref(false)

let autoSyncTimer: number | null = null

const fetchLimit = 500

const operatorLabels: Record<string, string> = {
  '1': '中国移动',
  '2': '中国联通',
  '3': '中国电信',
  '4': '中国广电'
}

function toOperatorLabel(code: string) {
  const key = (code || '').trim()
  return operatorLabels[key] || (key ? `运营商 ${key}` : '未知')
}

function formatDateTime(date: string, time: string) {
  const parts: string[] = []
  const d = (date || '').trim()
  if (d) {
    if (d.length === 8) {
      parts.push(`${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6, 8)}`)
    } else if (d.length === 6) {
      parts.push(`20${d.slice(0, 2)}-${d.slice(2, 4)}-${d.slice(4, 6)}`)
    } else {
      parts.push(d)
    }
  }
  const t = (time || '').trim()
  if (t) {
    if (t.length === 6) {
      parts.push(`${t.slice(0, 2)}:${t.slice(2, 4)}:${t.slice(4, 6)}`)
    } else {
      parts.push(t)
    }
  }
  return parts.join(' ') || '—'
}

const tableColumns = [
  { title: '时间', dataIndex: 'timestampDisplay', key: 'timestamp', width: 180 },
  { title: 'IMSI', dataIndex: 'imsi', key: 'imsi', width: 200 },
  { title: '运营商', dataIndex: 'operatorLabel', key: 'operator', width: 120 },
  { title: '区域', dataIndex: 'area', key: 'area', width: 140 },
  { title: '设备ID', dataIndex: 'deviceId', key: 'deviceId', width: 120 },
  { title: '来源文件', dataIndex: 'sourceFile', key: 'sourceFile', width: 220 },
  { title: '行号', dataIndex: 'lineNumber', key: 'lineNumber', width: 80 }
]

const filteredRecords = computed(() => {
  const device = (deviceFilter.value || '').trim().toLowerCase()
  const imsi = (imsiFilter.value || '').trim()
  return records.value.filter(record => {
    const deviceOk = !device || (record.deviceId || '').trim().toLowerCase() === device
    const imsiOk = !imsi || (record.imsi || '').includes(imsi)
    return deviceOk && imsiOk
  })
})

const tableData = computed<TableRow[]>(() => filteredRecords.value.map((record, index) => ({
  ...record,
  key: `${record.sourceFile || 'file'}-${record.lineNumber}-${index}`,
  operatorLabel: toOperatorLabel(record.operator),
  timestampDisplay: formatDateTime(record.rptDate, record.rptTime)
})))

const totalRecords = computed(() => filteredRecords.value.length)
const uniqueImsiCount = computed(() => {
  const set = new Set<string>()
  filteredRecords.value.forEach(rec => {
    if (rec.imsi) set.add(rec.imsi)
  })
  return set.size
})

const lastUpdatedDisplay = computed(() => {
  if (!lastUpdated.value) return '—'
  const date = new Date(lastUpdated.value)
  return Number.isNaN(date.getTime()) ? lastUpdated.value : date.toLocaleString()
})


const isConfigReady = computed(() => {
  return !!(imsiFtpHost.value && imsiFtpUser.value && imsiFtpPass.value)
})

function clearAutoSync() {
  if (autoSyncTimer) {
    window.clearInterval(autoSyncTimer)
    autoSyncTimer = null
  }
}

function scheduleAutoSync() {
  clearAutoSync()
  const seconds = Number(imsiSyncInterval.value) || 60
  if (!isConfigReady.value || seconds <= 0) return
  autoSyncTimer = window.setInterval(() => {
    void fetchImsiRecords(true)
  }, seconds * 1000)
}

async function fetchImsiRecords(silent = false) {
  loading.value = true
  error.value = null
  try {
    const resp = await fetch(`/api/imsi/records?limit=${fetchLimit}`)
    if (!resp.ok) {
      throw new Error(`请求失败 (${resp.status})`)
    }
    const data: any = await resp.json()
    metaMessage.value = data?.message ?? null
    elapsedMs.value = typeof data?.elapsedMs === 'number' ? data.elapsedMs : null
    records.value = Array.isArray(data?.records)
      ? data.records.map((item: any) => ({
        deviceId: String(item?.deviceId ?? ''),
        imsi: String(item?.imsi ?? ''),
        operator: String(item?.operator ?? ''),
        area: String(item?.area ?? ''),
        rptDate: String(item?.rptDate ?? ''),
        rptTime: String(item?.rptTime ?? ''),
        sourceFile: String(item?.sourceFile ?? ''),
        lineNumber: Number(item?.lineNumber ?? 0)
      })) as ImsiRecord[]
      : []
    lastUpdated.value = data?.timestamp ?? null
    sourceFiles.value = Array.isArray(data?.sourceFiles)
      ? data.sourceFiles.map((item: any) => String(item ?? ''))
      : []

    if (!data?.ok) {
      const msg = data?.message || '读取 IMSI 数据失败'
      error.value = msg
      if (!silent) message.warning(msg)
    } else {
      error.value = null
      if (!silent) message.success(data?.message || 'IMSI 数据已更新')
    }
  } catch (e: any) {
    const msg = e?.message || String(e)
    error.value = msg
    records.value = []
    sourceFiles.value = []
    if (!silent) message.error('读取 IMSI 数据失败：' + msg)
  } finally {
    loading.value = false
  }
}
async function refreshImsiRecords(silent = false) {
  if (syncing.value) return

  if (!isConfigReady.value) {
    if (!silent) message.error('请在设置 > IMSI 中填写 FTP 连接信息')
    await fetchImsiRecords(true)
    return
  }

  syncing.value = true
  try {
    const resp = await fetch('/api/imsi/sync', {
      method: 'POST'
    })
    if (!resp.ok) {
      throw new Error(`请求失败 (${resp.status})`)
    }
    const data: any = await resp.json()
    if (!data?.ok) {
      const msg = data?.message || 'FTP 同步失败'
      if (!silent) message.warning(msg)
    } else if (!silent) {
      message.success(data?.message || 'IMSI 数据已同步')
    }
  } catch (e: any) {
    const msg = e?.message || String(e)
    if (!silent) message.error('同步 IMSI 数据失败：' + msg)
  } finally {
    syncing.value = false
  }

  await fetchImsiRecords(true)
  scheduleAutoSync()
}


let configTimer: number | null = null

watch([imsiFtpHost, imsiFtpUser, imsiFtpPass, imsiDeviceFilter], () => {
  if (configTimer) window.clearTimeout(configTimer)
  configTimer = window.setTimeout(() => {
    void fetchImsiRecords(true)
  }, 600)
  scheduleAutoSync()
})

watch(imsiSyncInterval, () => {
  scheduleAutoSync()
})

onMounted(() => {
  void fetchImsiRecords(true)
  scheduleAutoSync()
})

onUnmounted(() => {
  if (configTimer) window.clearTimeout(configTimer)
  clearAutoSync()
})

const hasData = computed(() => !loading.value && !error.value && filteredRecords.value.length > 0)
const hasRawData = computed(() => records.value.length > 0)
</script>

<template>
  <a-layout style="min-height:calc(100vh - 64px); background:var(--bg-color); color:var(--text-color);">
    <a-layout-content style="padding:12px;">
      <a-card
        size="small"
        style="background:var(--panel-bg,#ffffff); border-color:var(--panel-border,#dddddd);"
      >
        <template #title>
          <a-space align="center">
            <span>IMSI 数据趋势</span>
            <a-tag v-if="totalRecords">{{ totalRecords }} 条</a-tag>
            <a-tag v-if="uniqueImsiCount" color="blue">唯一 IMSI：{{ uniqueImsiCount }}</a-tag>
          </a-space>
        </template>
        <template #extra>
          <a-space>
            <span>上次同步：{{ lastUpdatedDisplay }}</span>
            <a-input
              v-model:value="deviceFilter"
              style="width:160px"
              placeholder="筛选设备ID"
              :allowClear="true"
            />
            <a-input
              v-model:value="imsiFilter"
              style="width:200px"
              placeholder="筛选 IMSI"
              :allowClear="true"
            />
            <a-button :disabled="loading || syncing" @click="applyFilters">筛选</a-button>
            <a-button type="link" :loading="syncing" :disabled="loading || syncing" @click="refreshImsiRecords(false)">刷新</a-button>
          </a-space>
        </template>

        <a-spin :spinning="loading || syncing">
          <div v-if="!isConfigReady" style="margin-top:12px;">
            <a-alert type="info" show-icon message="请先在系统设置 → IMSI 中配置 FTP 连接信息。" />
          </div>
          <div v-else-if="error" style="margin-top:12px;">
            <a-alert type="error" show-icon :message="error" />
          </div>
          <div v-else>
            <div style="margin-bottom:12px;">
              <a-alert
                v-if="metaMessage"
                :type="hasData ? 'success' : 'info'"
                show-icon
                :message="metaMessage"
                :description="elapsedMs ? `耗时 ${elapsedMs} ms` : undefined"
              />
            </div>

            <a-alert
              v-if="!hasData"
              type="info"
              show-icon
              :message="hasRawData ? '筛选条件下没有数据，请调整设备ID。' : '尚未拉取到 IMSI 数据，请确认 FTP 已产生数据文件。'"
            />

            <a-table
              v-else
              :columns="tableColumns"
              :data-source="tableData"
              size="middle"
              :pagination="{ pageSize: 20, showTotal: total => `共 ${total} 条` }"
              bordered
              :scroll="{ x: 900 }"
            />
          </div>
        </a-spin>
      </a-card>
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
:deep(.ant-card-head-title) {
  color: var(--text-color);
}
:deep(.ant-card-extra) {
  color: var(--text-color-secondary, rgba(0,0,0,0.45));
}
</style>
