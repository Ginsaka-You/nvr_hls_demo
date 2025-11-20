<script setup lang="ts">
import { ref, reactive, computed, onMounted, h } from 'vue'
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
  snapshotUrl: string | null
  snapshots: string[]
  targetId?: number | null
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
  camChannel: string | null
  snapshotUrl: string | null
}

type TabKey = 'alerts' | 'camera' | 'radar'

const activeKey = ref<TabKey>('alerts')
const loading = reactive<Record<TabKey, boolean>>({ alerts: false, camera: false, radar: false })
const loaded = reactive<Record<TabKey, boolean>>({ alerts: false, camera: false, radar: false })

const alerts = ref<AlertRecord[]>([])
const camera = ref<CameraAlarmRecord[]>([])
const radar = ref<RadarRecord[]>([])
const previewVisible = ref(false)
const previewImages = ref<string[]>([])
const previewIndex = ref(0)

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
      alerts.value = aggregateAlertRecords(data.map(mapAlert))
    } else if (kind === 'camera') {
      camera.value = data.map(mapCameraAlarm)
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

function mapCameraAlarm(item: any): CameraAlarmRecord {
  const channelId = item?.channelId ?? item?.channel_id ?? null
  const port = item?.port ?? item?.camPort ?? null
  const fallbackChannel = channelId != null ? String(channelId) : port != null ? String(port) : null
  const eventType = translateEventType(item?.eventType)
  const snapshot = item?.snapshotUrl ?? item?.snapshot_url ?? null
  const snapshots = snapshot ? [snapshot] : []
  return {
    id: Number(item?.id ?? 0),
    eventId: String(item?.eventId ?? item?.id ?? '-'),
    eventType,
    camChannel: item?.camChannel ?? item?.cam_channel ?? fallbackChannel,
    level: item?.level ?? null,
    status: item?.status ?? '未处理',
    eventTime: item?.eventTime ?? null,
    createdAt: item?.createdAt ?? item?.created_at ?? new Date().toISOString(),
    device: '摄像头',
    snapshotUrl: snapshot,
    snapshots
  }
}

function mapAlert(item: any): AlertRecord {
  const channelId = item?.channelId ?? item?.channel_id ?? null
  const port = item?.port ?? item?.camPort ?? null
  const fallbackChannel = channelId != null ? String(channelId) : port != null ? String(port) : null
  const eventType = translateEventType(item?.eventType)
  const snapshot = item?.snapshotUrl ?? item?.snapshot_url ?? null
  const snapshots = Array.isArray(item?.snapshots)
    ? item.snapshots.filter((url: any) => typeof url === 'string' && url.length > 0)
    : snapshot
      ? [snapshot]
      : []
  const device = eventType && eventType.includes('雷达') ? '雷达' : '摄像头'
  const targetId = normalizeTargetId(item?.targetId ?? item?.target_id ?? parseTargetId(eventType))
  return {
    id: Number(item?.id ?? 0),
    eventId: String(item?.eventId ?? item?.id ?? '-'),
    eventType,
    camChannel: item?.camChannel ?? item?.cam_channel ?? fallbackChannel,
    level: item?.level ?? null,
    status: item?.status ?? '未处理',
    eventTime: item?.eventTime ?? null,
    createdAt: item?.createdAt ?? item?.created_at ?? new Date().toISOString(),
    device,
    snapshotUrl: snapshot,
    snapshots,
    targetId
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
    capturedAt: item?.capturedAt ?? item?.captured_at ?? new Date().toISOString(),
    camChannel: item?.camChannel ?? item?.cam_channel ?? null,
    snapshotUrl: item?.snapshotUrl ?? item?.snapshot_url ?? null
  }
}

function aggregateAlertRecords(items: AlertRecord[]): AlertRecord[] {
  const RADAR_MERGE_WINDOW_MS = 8000
  const dedupe = (list: string[]) => Array.from(new Set(list.filter(Boolean)))
  const sorted = [...items].sort((a, b) => getTimestamp(a.createdAt) - getTimestamp(b.createdAt))
  const grouped = new Map<string, { record: AlertRecord; lastTs: number }>()
  const result: AlertRecord[] = []

  for (const item of sorted) {
    const snapshots = dedupe((item.snapshots && item.snapshots.length ? item.snapshots : [item.snapshotUrl]).filter(Boolean))
    item.snapshots = snapshots
    item.snapshotUrl = snapshots[0] || item.snapshotUrl || null
    const targetId = item.targetId ?? parseTargetId(item.eventType)
    const radarKey = targetId != null ? `radar-${targetId}` : null
    if (!radarKey) {
      result.push(item)
      continue
    }
    const ts = getTimestamp(item.createdAt || item.eventTime)
    const existing = grouped.get(radarKey)
    if (existing && ts - existing.lastTs <= RADAR_MERGE_WINDOW_MS) {
      existing.record.snapshots = dedupe([...(existing.record.snapshots || []), ...snapshots])
      existing.record.snapshotUrl = existing.record.snapshots[0] || existing.record.snapshotUrl
      if (ts > getTimestamp(existing.record.createdAt)) {
        existing.record.createdAt = item.createdAt
        existing.record.eventTime = item.eventTime
        existing.record.status = item.status
      }
      existing.lastTs = ts
      continue
    }
    const merged: AlertRecord = {
      ...item,
      eventType: item.eventType || (targetId != null ? `雷达目标 #${targetId}` : item.eventType),
      device: '雷达',
      snapshots: [...snapshots],
      snapshotUrl: snapshots[0] || null,
      targetId
    }
    grouped.set(radarKey, { record: merged, lastTs: ts })
    result.push(merged)
  }

  return result.sort((a, b) => getTimestamp(b.createdAt) - getTimestamp(a.createdAt))
}

function normalizeTargetId(value: any): number | null {
  if (value === null || value === undefined) return null
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

function parseTargetId(eventType: string | null | undefined): number | null {
  if (!eventType) return null
  const match = eventType.match(/#\s*(\d+)/)
  if (!match) return null
  return normalizeTargetId(match[1])
}

function getTimestamp(value: string | null | undefined): number {
  if (!value) return 0
  const ts = new Date(value).getTime()
  return Number.isFinite(ts) ? ts : 0
}

function renderSnapshotCell(record: AlertRecord) {
  const images = record.snapshots && record.snapshots.length ? record.snapshots : (record.snapshotUrl ? [record.snapshotUrl] : [])
  if (!images.length) {
    return h('span', { class: 'snapshot-placeholder' }, '—')
  }
  const thumbs = images.slice(0, 4).map((url, index) =>
    h('button', {
      type: 'button',
      class: 'snapshot-thumb-btn',
      onClick: () => openPreviewGroup(images, index)
    }, [
      h('img', {
        src: url,
        alt: 'snapshot',
        class: 'snapshot-thumb',
        style: {
          width: '60px',
          height: '34px'
        }
      })
    ])
  )
  const remaining = images.length - thumbs.length
  return h('div', { class: 'snapshot-grid' }, [
    ...thumbs,
    remaining > 0
      ? h('span', { class: 'snapshot-more' }, `+${remaining}`)
      : null
  ])
}

function ensureLoaded(key: TabKey) {
  if (!loaded[key]) {
    void fetchData(key)
  }
}

onMounted(() => {
  ensureLoaded(activeKey.value)
})

function openPreviewGroup(images: string[], index = 0) {
  if (!images.length) return
  previewImages.value = images
  previewIndex.value = index
  previewVisible.value = true
}

const currentPreviewImage = computed(() => previewImages.value[previewIndex.value] || null)

function nextPreview() {
  if (!previewImages.value.length) return
  previewIndex.value = (previewIndex.value + 1) % previewImages.value.length
}

function prevPreview() {
  if (!previewImages.value.length) return
  previewIndex.value = (previewIndex.value - 1 + previewImages.value.length) % previewImages.value.length
}

const snapshotColumn = {
  title: '抓拍',
  key: 'snapshot',
  width: 150,
  customRender: ({ record }: { record: AlertRecord }) => renderSnapshotCell(record)
}

const alertColumns = computed(() => [
  snapshotColumn,
  { title: '事件类型', dataIndex: 'eventType', key: 'eventType', width: 200 },
  { title: '设备', dataIndex: 'device', key: 'device', width: 100 },
  { title: '摄像头通道', dataIndex: 'camChannel', key: 'camChannel', width: 140 },
  { title: '等级', dataIndex: 'level', key: 'level', width: 100 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '事件时间', dataIndex: 'eventTime', key: 'eventTime', width: 180, customRender: ({ text }: any) => formatDate(text) },
  { title: '收到时间', dataIndex: 'createdAt', key: 'createdAt', width: 180, customRender: ({ text }: any) => formatDate(text) }
])

const radarColumns = computed(() => [
  snapshotColumn,
  { title: '设备', dataIndex: 'device', key: 'device', width: 100 },
  { title: '雷达', dataIndex: 'radarHost', key: 'radarHost', width: 140 },
  { title: '摄像头通道', dataIndex: 'camChannel', key: 'camChannel', width: 120 },
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
  if ((lower.includes('region') && (lower.includes('entrance') || lower.includes('enter'))) || lower.includes('areaenter')) {
    return '进入区域侦测'
  }
  if ((lower.includes('region') && (lower.includes('exit') || lower.includes('leave') || lower.includes('depart'))) || lower.includes('areaexit')) {
    return '离开区域侦测'
  }
  if (lower.includes('loiter') || lower.includes('linger') || lower.includes('stay')) {
    return '徘徊侦测'
  }
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
    <a-modal v-model:visible="previewVisible" :footer="null" width="60vw" centered destroy-on-close @cancel="previewVisible = false">
      <div class="preview-body" v-if="currentPreviewImage">
        <button class="preview-nav left" type="button" @click="prevPreview" v-if="previewImages.length > 1">‹</button>
        <img :src="currentPreviewImage" alt="snapshot preview" />
        <button class="preview-nav right" type="button" @click="nextPreview" v-if="previewImages.length > 1">›</button>
        <div class="preview-counter" v-if="previewImages.length > 1">
          {{ previewIndex + 1 }} / {{ previewImages.length }}
        </div>
      </div>
    </a-modal>
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

.snapshot-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 4px;
}

.snapshot-thumb-btn {
  border: none;
  padding: 0;
  background: transparent;
  cursor: pointer;
}

.snapshot-thumb {
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
}

.snapshot-more {
  font-size: 12px;
  color: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
}

.snapshot-placeholder {
  color: rgba(0, 0, 0, 0.35);
}

.preview-body {
  position: relative;
  width: 100%;
  text-align: center;
}

.preview-body img {
  max-width: 100%;
  border-radius: 6px;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.28);
}

.preview-nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  border: none;
  background: rgba(0, 0, 0, 0.4);
  color: #fff;
  width: 32px;
  height: 48px;
  font-size: 24px;
  cursor: pointer;
  border-radius: 4px;
}

.preview-nav.left {
  left: 8px;
}

.preview-nav.right {
  right: 8px;
}

.preview-counter {
  margin-top: 8px;
  color: rgba(0, 0, 0, 0.65);
}
</style>
