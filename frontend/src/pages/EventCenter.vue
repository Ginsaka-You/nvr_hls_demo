<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'

type AlertRecord = {
  id: number
  eventId: string
  eventType: string | null
  camChannel: string | null
  level: string | null
  status: string | null
  eventTime: string | null
  createdAt: string
  device: string
}

type CameraAlarmRecord = AlertRecord

type RadarRecord = {
  id: number
  device: string
  radarHost: string | null
  controlPort: number | null
  dataPort: number | null
  actualDataPort: number | null
  transportTcp: boolean
  targetId: number | null
  targetCount: number | null
  longitudinalDistance: number | null
  lateralDistance: number | null
  speed: number | null
  range: number | null
  angle: number | null
  amplitude: number | null
  snr: number | null
  rcs: number | null
  capturedAt: string
}

type TabKey = 'alerts' | 'camera' | 'radar'

const activeKey = ref<TabKey>('alerts')
const loading = reactive<Record<TabKey, boolean>>({ alerts: false, camera: false, radar: false })
const loaded = reactive<Record<TabKey, boolean>>({ alerts: false, camera: false, radar: false })

const alerts = ref<AlertRecord[]>([])
const camera = ref<CameraAlarmRecord[]>([])
const radar = ref<RadarRecord[]>([])

function formatDate(value: string | null | undefined) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

async function fetchData(kind: TabKey) {
  if (loading[kind]) return
  loading[kind] = true
  try {
    const resp = await fetch(`/api/events/${kind === 'alerts' ? 'alerts' : kind === 'camera' ? 'camera-alarms' : 'radar-targets'}?limit=200`)
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
    const data = await resp.json()
    if (!Array.isArray(data)) {
      throw new Error('数据格式异常')
    }
    if (kind === 'alerts') {
      alerts.value = data.map(mapAlert)
    } else if (kind === 'camera') {
      camera.value = data.map(mapAlert)
    } else {
      radar.value = data.map(mapRadar)
    }
    loaded[kind] = true
  } catch (err: any) {
    message.error(`加载失败：${err?.message || err}`)
  } finally {
    loading[kind] = false
  }
}

function mapAlert(item: any): AlertRecord {
  const channelId = item?.channelId ?? item?.channel_id ?? null
  const port = item?.port ?? item?.camPort ?? null
  const fallbackChannel = channelId != null ? String(channelId) : port != null ? String(port) : null
  const eventType = translateEventType(item?.eventType)
  return {
    id: Number(item?.id ?? 0),
    eventId: String(item?.id ?? item?.eventId ?? '-'),
    eventType,
    camChannel: item?.camChannel ?? item?.cam_channel ?? fallbackChannel,
    level: item?.level ?? null,
    status: item?.status ?? '未处理',
    eventTime: item?.eventTime ?? null,
    createdAt: item?.createdAt ?? item?.created_at ?? new Date().toISOString(),
    device: eventType === '检测到入侵' ? '雷达' : '摄像头'
  }
}

function mapRadar(item: any): RadarRecord {
  return {
    id: Number(item?.id ?? 0),
    device: '雷达',
    radarHost: item?.radarHost ?? null,
    controlPort: item?.controlPort ?? null,
    dataPort: item?.dataPort ?? null,
    actualDataPort: item?.actualDataPort ?? null,
    transportTcp: Boolean(item?.transportTcp),
    targetId: item?.targetId ?? null,
    targetCount: item?.targetCount ?? null,
    longitudinalDistance: item?.longitudinalDistance ?? null,
    lateralDistance: item?.lateralDistance ?? null,
    speed: item?.speed ?? null,
    range: item?.range ?? null,
    angle: item?.angle ?? null,
    amplitude: item?.amplitude ?? null,
    snr: item?.snr ?? null,
    rcs: item?.rcs ?? null,
    capturedAt: item?.capturedAt ?? item?.captured_at ?? new Date().toISOString()
  }
}

function ensureLoaded(key: TabKey) {
  if (!loaded[key]) {
    void fetchData(key)
  }
}

onMounted(() => {
  ensureLoaded(activeKey.value)
})

const alertColumns = computed(() => [
  { title: '事件ID', dataIndex: 'eventId', key: 'eventId', width: 160 },
  { title: '事件类型', dataIndex: 'eventType', key: 'eventType', width: 180 },
  { title: '设备', dataIndex: 'device', key: 'device', width: 100 },
  { title: '摄像头通道', dataIndex: 'camChannel', key: 'camChannel', width: 140 },
  { title: '等级', dataIndex: 'level', key: 'level', width: 100 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '时间', key: 'timeline', width: 220, customRender: ({ record }: { record: AlertRecord }) => formatTimeline(record) }
])

const radarColumns = computed(() => [
  { title: '设备', dataIndex: 'device', key: 'device', width: 100 },
  { title: '雷达', dataIndex: 'radarHost', key: 'radarHost', width: 140 },
  { title: '控制端口', dataIndex: 'controlPort', key: 'controlPort', width: 100 },
  { title: '数据端口', dataIndex: 'dataPort', key: 'dataPort', width: 100 },
  { title: '目标ID', dataIndex: 'targetId', key: 'targetId', width: 100 },
  { title: '目标数', dataIndex: 'targetCount', key: 'targetCount', width: 100 },
  { title: '纵向距离', dataIndex: 'longitudinalDistance', key: 'longitudinalDistance', width: 120 },
  { title: '横向距离', dataIndex: 'lateralDistance', key: 'lateralDistance', width: 120 },
  { title: '速度', dataIndex: 'speed', key: 'speed', width: 100 },
  { title: '距离', dataIndex: 'range', key: 'range', width: 100 },
  { title: '角度', dataIndex: 'angle', key: 'angle', width: 100 },
  { title: '记录时间', dataIndex: 'capturedAt', key: 'capturedAt', width: 180, customRender: ({ text }: any) => formatDate(text) }
])

function onTabChange(key: string) {
  const typed = key as TabKey
  activeKey.value = typed
  ensureLoaded(typed)
}

const pagination = { pageSize: 20, showSizeChanger: false }

function formatTimeline(record: AlertRecord) {
  const eventTime = formatDate(record.eventTime)
  if (eventTime && eventTime !== '-') {
    return eventTime
  }
  return '-'
}

function translateEventType(value: any): string | null {
  if (value == null) return null
  const text = String(value).trim()
  const lower = text.toLowerCase()
  if (lower === 'radar') return '检测到入侵'
  if (lower === 'fielddetection') return '检测到区域入侵'
  return text
}
</script>

<template>
  <div class="event-center">
    <a-tabs v-model:activeKey="activeKey" type="card" @change="onTabChange">
      <a-tab-pane key="alerts" tab="告警事件">
        <a-table
          row-key="id"
          :columns="alertColumns"
          :data-source="alerts"
          :loading="loading.alerts"
          :pagination="pagination"
        />
      </a-tab-pane>
      <a-tab-pane key="camera" tab="摄像头告警">
        <a-table
          row-key="id"
          :columns="alertColumns"
          :data-source="camera"
          :loading="loading.camera"
          :pagination="pagination"
        />
      </a-tab-pane>
      <a-tab-pane key="radar" tab="雷达目标">
        <a-table
          row-key="id"
          :columns="radarColumns"
          :data-source="radar"
          :loading="loading.radar"
          :pagination="pagination"
        />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<style scoped>
.event-center {
  padding: 16px;
  background: var(--bg-color, #fff);
  min-height: calc(100vh - 64px);
}

table {
  width: 100%;
}
</style>
