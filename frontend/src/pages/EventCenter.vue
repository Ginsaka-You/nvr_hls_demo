<script setup lang="ts">
import { ref, reactive, computed, onMounted, h, watch } from 'vue'
import { message } from 'ant-design-vue'

type Classification = 'P1' | 'P2' | 'P3' | 'P4' | 'INFO'

const classificationMeta: Record<Classification, { label: string }> = {
  P1: { label: 'P1 最高优先级' },
  P2: { label: 'P2 高优先级' },
  P3: { label: 'P3 中等优先级' },
  P4: { label: 'P4 低优先级' },
  INFO: { label: '信息留存' }
}

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

type RiskActionRecord = {
  id: string | number
  eventId: string
  assessmentId?: number | string | null
  action: string | null
  actions?: string[]
  eventTimeRange?: { start: string | null; end: string | null }
  eventType: string | null
  camChannel: string | null
  level: string | null
  status: string | null
  eventTime: string | null
  createdAt: string
  snapshotUrl: string | null
  snapshots: string[]
  classification: string | null
  score: number | null
  summary: string | null
  remoteAlarmGateTriggered?: boolean | null
  soundLightTriggered?: boolean | null
  details: any
  windowStart?: string | null
  windowEnd?: string | null
  decidedAt?: string | null
}

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

type TabKey = 'alerts' | 'risk' | 'camera' | 'radar'

const activeKey = ref<TabKey>('risk')
const loading = reactive<Record<TabKey, boolean>>({ alerts: false, risk: false, camera: false, radar: false })
const loaded = reactive<Record<TabKey, boolean>>({ alerts: false, risk: false, camera: false, radar: false })
const FETCH_LIMIT = 500

const alerts = ref<AlertRecord[]>([])
const riskActionsRaw = ref<RiskActionRecord[]>([])
const riskActionsA1Loaded = ref(false)
const mergedRiskActions = computed(() => mergeRiskActions(riskActionsRaw.value))
const showA1 = ref(false)
const filteredRiskActions = computed(() =>
  showA1.value
    ? mergedRiskActions.value
    : mergedRiskActions.value.filter(item => {
        const actions = getRecordActions(item)
        if (!actions.length) return true
        return actions.some(action => action !== 'A1')
      })
)
const camera = ref<CameraAlarmRecord[]>([])
const radar = ref<RadarRecord[]>([])
const previewVisible = ref(false)
const previewImages = ref<string[]>([])
const previewIndex = ref(0)

watch(showA1, async (value) => {
  if (!value) {
    return
  }
  await appendRiskActionsA1()
})

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
    if (kind === 'risk') {
      riskActionsRaw.value = await fetchRiskActionsByTypes(['A2', 'A3'])
      riskActionsA1Loaded.value = false
      if (showA1.value) {
        await appendRiskActionsA1()
      }
    } else {
      const endpoint = kind === 'alerts'
        ? 'alerts'
        : kind === 'camera'
          ? 'camera-alarms'
          : 'radar-targets'
      const resp = await fetch(`/api/events/${endpoint}?limit=${FETCH_LIMIT}`)
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
      const data = await resp.json()
      if (!Array.isArray(data)) {
        throw new Error('数据格式异常')
      }
      if (kind === 'alerts') {
        const riskForMerge = await fetchRiskActionsForMerge()
        const riskIndex = buildRiskActionTriggerIndex(riskForMerge)
        alerts.value = aggregateAlertRecords(data.map(mapAlert), riskIndex)
      } else if (kind === 'camera') {
        camera.value = data.map(mapCameraAlarm)
      } else {
        radar.value = data.map(mapRadar)
      }
    }
    loaded[kind] = true
  } catch (err: any) {
    message.error(`加载失败：${err?.message || err}`)
  } finally {
    loading[kind] = false
  }
}

async function fetchRiskActionsForMerge(): Promise<RiskActionRecord[]> {
  return fetchRiskActionsByTypes(['A2', 'A3'])
}

async function fetchRiskActionsByTypes(types: string[]): Promise<RiskActionRecord[]> {
  try {
    const params = new URLSearchParams({ limit: String(FETCH_LIMIT) })
    if (types.length) {
      params.set('actions', types.join(','))
    }
    const resp = await fetch(`/api/events/risk-actions?${params.toString()}`)
    if (!resp.ok) return []
    const data = await resp.json()
    if (!Array.isArray(data)) return []
    return data.map(mapRiskAction)
  } catch {
    return []
  }
}

async function appendRiskActionsA1() {
  if (riskActionsA1Loaded.value) {
    return
  }
  const a1Items = await fetchRiskActionsByTypes(['A1'])
  if (a1Items.length) {
    riskActionsRaw.value = [...riskActionsRaw.value, ...a1Items]
  }
  riskActionsA1Loaded.value = true
}

function mapCameraAlarm(item: any): CameraAlarmRecord {
  const channelId = item?.channelId ?? item?.channel_id ?? null
  const port = item?.port ?? item?.camPort ?? null
  const fallbackChannel = channelId != null ? String(channelId) : port != null ? String(port) : null
  const eventType = translateEventType(item?.eventType)
  const snapshot = item?.snapshotUrl ?? item?.snapshot_url ?? null
  const snapshots = snapshot ? [snapshot] : []
  const dedupedSnapshots = dedupeSnapshots(snapshots)
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
    snapshotUrl: dedupedSnapshots[0] ?? snapshot,
    snapshots: dedupedSnapshots
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
  const dedupedSnapshots = dedupeSnapshots(snapshots)
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
    snapshotUrl: dedupedSnapshots[0] ?? snapshot,
    snapshots: dedupedSnapshots,
    targetId
  }
}

function mapRiskAction(item: any): RiskActionRecord {
  const action = normalizeAction(parseRiskAction(item))
  const classification = item?.classification ?? null
  const score = typeof item?.score === 'number' ? item.score : (item?.score != null ? Number(item.score) : null)
  const summary = item?.summary ?? null
  const remoteAlarmGateTriggered = typeof item?.remoteAlarmGateTriggered === 'boolean'
    ? item.remoteAlarmGateTriggered
    : (item?.remote_alarm_gate_triggered != null ? Boolean(item.remote_alarm_gate_triggered) : null)
  const soundLightTriggered = typeof item?.soundLightTriggered === 'boolean'
    ? item.soundLightTriggered
    : (item?.sound_light_triggered != null ? Boolean(item.sound_light_triggered) : null)
  const snapshots = Array.isArray(item?.snapshots)
    ? item.snapshots.filter((url: any) => typeof url === 'string' && url.length > 0)
    : item?.snapshotUrl
      ? [item.snapshotUrl]
      : []
  const dedupedSnapshots = dedupeSnapshots(snapshots)
  const eventId = String(item?.eventId ?? item?.id ?? `risk-${Date.now().toString(36)}`)
  const eventType = item?.eventType ?? formatRiskEventType(action, classification)
  const eventTime = item?.eventTime ?? item?.decidedAt ?? null
  return {
    id: item?.id ?? eventId,
    eventId,
    assessmentId: item?.assessmentId ?? item?.assessment_id ?? null,
    action,
    actions: action ? [action] : [],
    eventType,
    camChannel: item?.camChannel ?? item?.cam_channel ?? null,
    level: item?.level ?? null,
    status: item?.status ?? '未处理',
    eventTime,
    createdAt: item?.createdAt ?? item?.created_at ?? new Date().toISOString(),
    snapshotUrl: dedupedSnapshots[0] ?? item?.snapshotUrl ?? null,
    snapshots: dedupedSnapshots,
    classification,
    score,
    summary,
    remoteAlarmGateTriggered,
    soundLightTriggered,
    details: normalizeDetails(item?.details ?? item?.detailsJson ?? item?.details_json),
    windowStart: item?.windowStart ?? null,
    windowEnd: item?.windowEnd ?? null,
    decidedAt: item?.decidedAt ?? null
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

function buildRiskActionTriggerIndex(items: RiskActionRecord[]): Map<string, boolean> {
  const index = new Map<string, boolean>()
  for (const item of items) {
    const key = typeof item.eventId === 'string' ? item.eventId : null
    if (!key) {
      continue
    }
    if (item.soundLightTriggered === true) {
      index.set(key, true)
    } else if (!index.has(key)) {
      index.set(key, false)
    }
  }
  return index
}

function aggregateAlertRecords(items: AlertRecord[], riskIndex?: Map<string, boolean>): AlertRecord[] {
  const RADAR_MERGE_WINDOW_MS = 8000
  const sorted = [...items].sort((a, b) => getTimestamp(a.createdAt) - getTimestamp(b.createdAt))
  const grouped = new Map<string, { record: AlertRecord; lastTs: number }>()
  const result: AlertRecord[] = []

  for (const item of sorted) {
    const snapshots = dedupeSnapshots((item.snapshots && item.snapshots.length ? item.snapshots : [item.snapshotUrl]).filter(Boolean))
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
      existing.record.snapshots = dedupeSnapshots([...(existing.record.snapshots || []), ...snapshots])
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

  const mergedRisk = mergeRiskAlertRecords(result, riskIndex)
  return mergedRisk.sort((a, b) => getTimestamp(b.createdAt) - getTimestamp(a.createdAt))
}

function mergeRiskAlertRecords(items: AlertRecord[], riskIndex?: Map<string, boolean>): AlertRecord[] {
  const COOLDOWN_WINDOW_MS = 10 * 60 * 1000
  const riskItems = items.filter(isRiskAlertRecord)
  if (!riskItems.length) {
    return items
  }
  const otherItems = items.filter(item => !isRiskAlertRecord(item))
  const sorted = [...riskItems].sort((a, b) => getAlertTimestamp(a) - getAlertTimestamp(b))
  const groups: AlertRecord[][] = []
  let currentGroup: AlertRecord[] | null = null
  let currentLastTime = 0

  for (const item of sorted) {
    const action = extractRiskActionFromAlert(item)
    const timeMs = getAlertTimestamp(item)
    if (!action || timeMs <= 0) {
      groups.push([item])
      currentGroup = null
      currentLastTime = 0
      continue
    }
    const soundLightTriggered = riskIndex ? riskIndex.get(item.eventId) === true : false
    const startNewByTrigger = action === 'A2' && soundLightTriggered
    const startNewByGap = currentGroup && timeMs - currentLastTime > COOLDOWN_WINDOW_MS
    if (!currentGroup || startNewByTrigger || startNewByGap) {
      currentGroup = [item]
      groups.push(currentGroup)
    } else {
      currentGroup.push(item)
    }
    currentLastTime = timeMs
  }

  const mergedRisk = groups.map(group => (group.length > 1 ? buildMergedRiskAlert(group) : group[0]))
  return [...otherItems, ...mergedRisk]
}

function buildMergedRiskAlert(groupItems: AlertRecord[]): AlertRecord {
  const actions = Array.from(new Set(groupItems.map(extractRiskActionFromAlert).filter(Boolean))) as string[]
  actions.sort((a, b) => actionPriority(a) - actionPriority(b))
  const snapshotMap = new Map<string, string>()
  for (const item of groupItems) {
    const list = item.snapshots && item.snapshots.length ? item.snapshots : (item.snapshotUrl ? [item.snapshotUrl] : [])
    list.filter(Boolean).forEach(url => {
      const key = normalizeSnapshotKey(url)
      if (!key) return
      if (!snapshotMap.has(key)) {
        snapshotMap.set(key, url)
      }
    })
  }
  const snapshots = Array.from(snapshotMap.values())
  const primary = pickPrimaryRiskAlert(groupItems)
  const latestEventTime = pickLatestValue(groupItems, item => item.eventTime ?? item.createdAt)
  const latestCreatedAt = pickLatestValue(groupItems, item => item.createdAt)
  return {
    ...primary,
    eventType: actions.length ? `风控动作 ${actions.join('/')}` : primary.eventType,
    snapshots,
    snapshotUrl: snapshots[0] ?? primary.snapshotUrl ?? null,
    eventTime: latestEventTime ?? primary.eventTime,
    createdAt: latestCreatedAt ?? primary.createdAt,
  }
}

function pickLatestValue(items: AlertRecord[], pick: (item: AlertRecord) => string | null | undefined): string | null {
  let best: string | null = null
  let bestTs = -1
  for (const item of items) {
    const value = pick(item)
    const ts = getTimestamp(value)
    if (ts > bestTs) {
      bestTs = ts
      best = value ?? null
    }
  }
  return best
}

function pickPrimaryRiskAlert(items: AlertRecord[]): AlertRecord {
  return items.reduce((best, current) => {
    const bestAction = extractRiskActionFromAlert(best)
    const currentAction = extractRiskActionFromAlert(current)
    const bestRank = actionPriority(bestAction)
    const currentRank = actionPriority(currentAction)
    if (currentRank > bestRank) {
      return current
    }
    if (currentRank < bestRank) {
      return best
    }
    const bestTs = getAlertTimestamp(best)
    const currentTs = getAlertTimestamp(current)
    return currentTs > bestTs ? current : best
  }, items[0])
}

function getAlertTimestamp(item: AlertRecord): number {
  return getTimestamp(item.eventTime ?? item.createdAt)
}

function isRiskAlertRecord(item: AlertRecord): boolean {
  const eventId = typeof item.eventId === 'string' ? item.eventId : ''
  if (eventId.toLowerCase().startsWith('risk-')) return true
  if (typeof item.eventType === 'string' && item.eventType.includes('风控动作')) return true
  return false
}

function extractRiskActionFromAlert(item: AlertRecord): string | null {
  const eventId = typeof item.eventId === 'string' ? item.eventId : ''
  const idMatch = eventId.match(/risk-?a?([1-3])/i)
  if (idMatch && idMatch[1]) {
    return `A${idMatch[1]}`
  }
  if (typeof item.eventType === 'string') {
    const typeMatch = item.eventType.match(/A[1-3]/i)
    if (typeMatch && typeMatch[0]) {
      return typeMatch[0].toUpperCase()
    }
  }
  return null
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

function renderSnapshotCell(record: { snapshotUrl?: string | null; snapshots?: string[] }) {
  const rawImages = record.snapshots && record.snapshots.length ? record.snapshots : (record.snapshotUrl ? [record.snapshotUrl] : [])
  const images = dedupeSnapshots(rawImages)
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
  void logSnapshotView(images[index] || null)
}

const currentPreviewImage = computed(() => previewImages.value[previewIndex.value] || null)

function nextPreview() {
  if (!previewImages.value.length) return
  const nextIndex = (previewIndex.value + 1) % previewImages.value.length
  previewIndex.value = nextIndex
  void logSnapshotView(previewImages.value[nextIndex] || null)
}

function prevPreview() {
  if (!previewImages.value.length) return
  const prevIndex = (previewIndex.value - 1 + previewImages.value.length) % previewImages.value.length
  previewIndex.value = prevIndex
  void logSnapshotView(previewImages.value[prevIndex] || null)
}

async function logSnapshotView(path: string | null) {
  if (!path || activeKey.value !== 'risk') return
  try {
    await fetch('/api/events/snapshots/view', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path, context: 'risk' })
    })
  } catch {
    // ignore logging errors
  }
}

const snapshotColumn = {
  title: '抓拍',
  key: 'snapshot',
  width: 150,
  customRender: ({ record }: { record: any }) => renderSnapshotCell(record)
}

const alertColumns = computed(() => [
  snapshotColumn,
  { title: '事件类型', dataIndex: 'eventType', key: 'eventType', width: 200 },
  { title: '设备', dataIndex: 'device', key: 'device', width: 100 },
  { title: '摄像头通道', dataIndex: 'camChannel', key: 'camChannel', width: 140 },
  { title: '等级', dataIndex: 'level', key: 'level', width: 100 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '事件时间', dataIndex: 'eventTime', key: 'eventTime', width: 180, customRender: ({ text }: any) => formatDate(text) }
])

const riskColumns = computed(() => {
  const riskSnapshotColumn = { ...snapshotColumn, width: 110 }
  return [
    riskSnapshotColumn,
    { title: '风控动作', dataIndex: 'action', key: 'action', customRender: ({ record }: any) => renderRiskActionCell(record) },
    { title: '优先级', dataIndex: 'classification', key: 'classification', customRender: ({ record }: any) => formatClassification(record.classification) },
    { title: '综合得分', dataIndex: 'score', key: 'score', customRender: ({ record }: any) => formatScoreValue(record.score) },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
    { title: '远程警报闸门', dataIndex: 'remoteAlarmGateTriggered', key: 'remoteAlarmGateTriggered', customRender: ({ record }: any) => renderFlag(record.remoteAlarmGateTriggered) },
    { title: '声光报警', dataIndex: 'soundLightTriggered', key: 'soundLightTriggered', customRender: ({ record }: any) => renderFlag(record.soundLightTriggered, '已触发', '未触发') },
    { title: '评分详情', key: 'details', customRender: ({ record }: any) => renderRiskDetail(record) },
    { title: '摄像头通道', dataIndex: 'camChannel', key: 'camChannel' },
    { title: '事件时间', dataIndex: 'eventTime', key: 'eventTime', customRender: ({ record }: any) => renderRiskEventTime(record) }
  ]
})

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
const tableScroll = { x: '100%' as const }
const riskTableScroll = undefined

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

function normalizeAction(action: string | null | undefined): string | null {
  if (!action) return null
  const match = action.toUpperCase().match(/A[1-3]/)
  return match ? match[0] : null
}

function safeDecodeUrlSegment(value: string): string {
  try {
    return decodeURIComponent(value.replace(/\+/g, '%20'))
  } catch {
    return value
  }
}

function normalizeSnapshotKey(url: string | null | undefined): string | null {
  if (!url) return null
  let trimmed = url.trim()
  if (!trimmed) return null
  const originMatch = trimmed.match(/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/]+(\/.*)?$/)
  if (originMatch) {
    trimmed = originMatch[1] || '/'
  }
  let cut = trimmed.length
  const queryIdx = trimmed.indexOf('?')
  const hashIdx = trimmed.indexOf('#')
  if (queryIdx >= 0) cut = Math.min(cut, queryIdx)
  if (hashIdx >= 0) cut = Math.min(cut, hashIdx)
  if (cut !== trimmed.length) {
    trimmed = trimmed.slice(0, cut)
  }
  trimmed = trimmed.replace(/\\/g, '/')
  const leadingSlash = trimmed.startsWith('/')
  const parts = trimmed.split('/')
  const decodedParts = parts
    .map((part) => safeDecodeUrlSegment(part))
    .filter((part) => part.length > 0)
  if (!decodedParts.length) return null
  let normalized = decodedParts.join('/')
  const markerIdx = normalized.indexOf('snapshots/')
  if (markerIdx >= 0) {
    normalized = normalized.slice(markerIdx + 'snapshots/'.length)
  }
  normalized = normalized.replace(/^\/+/, '')
  return normalized || null
}

function dedupeSnapshots(urls: string[]): string[] {
  const map = new Map<string, string>()
  for (const url of urls) {
    const key = normalizeSnapshotKey(url)
    if (!key) continue
    if (!map.has(key)) {
      map.set(key, url)
    }
  }
  return Array.from(map.values())
}

function actionPriority(action: string | null | undefined): number {
  if (!action) return -1
  if (action === 'A3') return 2
  if (action === 'A2') return 1
  if (action === 'A1') return 0
  return -1
}

function getRecordActions(record: RiskActionRecord): string[] {
  const raw = record.actions && record.actions.length ? record.actions : (record.action ? [record.action] : [])
  const normalized = raw.map(item => normalizeAction(item)).filter(Boolean) as string[]
  return Array.from(new Set(normalized)).sort((a, b) => actionPriority(a) - actionPriority(b))
}

function pickLatestTime(items: RiskActionRecord[], pick: (item: RiskActionRecord) => string | null | undefined): string | null {
  let best: string | null = null
  let bestTs = -1
  for (const item of items) {
    const value = pick(item)
    const ts = getTimestamp(value)
    if (ts > bestTs) {
      bestTs = ts
      best = value ?? null
    }
  }
  return best
}

function pickEarliestTime(items: RiskActionRecord[], pick: (item: RiskActionRecord) => string | null | undefined): string | null {
  let best: string | null = null
  let bestTs = Number.POSITIVE_INFINITY
  for (const item of items) {
    const value = pick(item)
    const ts = getTimestamp(value)
    if (ts > 0 && ts < bestTs) {
      bestTs = ts
      best = value ?? null
    }
  }
  return best
}

function pickPrimaryRiskAction(items: RiskActionRecord[]): RiskActionRecord {
  return items.reduce((best, current) => {
    const bestRank = actionPriority(best.action)
    const currentRank = actionPriority(current.action)
    if (currentRank > bestRank) {
      return current
    }
    if (currentRank < bestRank) {
      return best
    }
    const bestTs = getTimestamp(best.eventTime ?? best.decidedAt ?? best.createdAt)
    const currentTs = getTimestamp(current.eventTime ?? current.decidedAt ?? current.createdAt)
    return currentTs > bestTs ? current : best
  }, items[0])
}

function mergeRiskActions(items: RiskActionRecord[]): RiskActionRecord[] {
  const COOLDOWN_WINDOW_MS = 10 * 60 * 1000
  const result: RiskActionRecord[] = []
  const gateGroups: RiskActionRecord[][] = []
  let currentGroup: RiskActionRecord[] | null = null
  let currentGroupLastTime = 0

  const sorted = [...items].sort((a, b) => getTimestamp(a.eventTime ?? a.decidedAt ?? a.createdAt) - getTimestamp(b.eventTime ?? b.decidedAt ?? b.createdAt))
  for (const item of sorted) {
    const actions = getRecordActions(item)
    item.actions = actions
    item.action = actions[0] ?? null
    const action = actions.length === 1 ? actions[0] : null
    const timeMs = getTimestamp(item.eventTime ?? item.decidedAt ?? item.createdAt)
    const canMerge = action != null && (action === 'A2' || action === 'A3') && timeMs > 0
    if (!canMerge) {
      result.push(item)
      continue
    }

    const startNewByTrigger = action === 'A2' && item.soundLightTriggered === true
    const startNewByGap = currentGroup && timeMs - currentGroupLastTime > COOLDOWN_WINDOW_MS
    if (!currentGroup || startNewByTrigger || startNewByGap) {
      currentGroup = [item]
      gateGroups.push(currentGroup)
    } else {
      currentGroup.push(item)
    }
    currentGroupLastTime = timeMs
  }

  for (const group of gateGroups) {
    const groupItems = group
    if (groupItems.length === 1) {
      result.push(groupItems[0])
      continue
    }
    const actions = Array.from(new Set(groupItems.flatMap(getRecordActions)))
    actions.sort((a, b) => actionPriority(a) - actionPriority(b))
    const snapshotMap = new Map<string, string>()
    for (const item of groupItems) {
      const list = item.snapshots && item.snapshots.length ? item.snapshots : (item.snapshotUrl ? [item.snapshotUrl] : [])
      list.filter(Boolean).forEach(url => {
        const key = normalizeSnapshotKey(url)
        if (!key) return
        if (!snapshotMap.has(key)) {
          snapshotMap.set(key, url)
        }
      })
    }
    const snapshots = Array.from(snapshotMap.values())
    const primary = pickPrimaryRiskAction(groupItems)
    const earliest = pickEarliestTime(groupItems, (item) => item.eventTime ?? item.decidedAt ?? item.createdAt)
    const latest = pickLatestTime(groupItems, (item) => item.eventTime ?? item.decidedAt ?? item.createdAt)
    const merged: RiskActionRecord = {
      ...primary,
      action: actions.join('/'),
      actions,
      snapshots,
      snapshotUrl: snapshots[0] ?? primary.snapshotUrl ?? null,
      eventTime: latest ?? primary.eventTime,
      eventTimeRange: {
        start: earliest ?? primary.eventTime ?? null,
        end: latest ?? primary.eventTime ?? null
      },
      createdAt: pickLatestTime(groupItems, (item) => item.createdAt) ?? primary.createdAt,
      remoteAlarmGateTriggered: groupItems.some(item => item.remoteAlarmGateTriggered === true),
      soundLightTriggered: groupItems.some(item => item.soundLightTriggered === true)
    }
    result.push(merged)
  }

  return result.sort((a, b) => getTimestamp(b.eventTime ?? b.createdAt) - getTimestamp(a.eventTime ?? a.createdAt))
}

function parseRiskAction(item: any): string | null {
  const direct = item?.action ?? item?.actionId ?? item?.action_id
  if (typeof direct === 'string' && direct.trim()) {
    const match = direct.trim().toUpperCase().match(/A[1-3]/)
    if (match) return match[0]
  }
  const eventId = typeof item?.eventId === 'string' ? item.eventId : (typeof item?.event_id === 'string' ? item.event_id : '')
  const idMatch = eventId.match(/risk-?a?([1-3])/i)
  if (idMatch && idMatch[1]) {
    return `A${idMatch[1]}`
  }
  const typeMatch = typeof item?.eventType === 'string' ? item.eventType.match(/A[1-3]/i) : null
  if (typeMatch && typeMatch[0]) {
    return typeMatch[0].toUpperCase()
  }
  return null
}

function renderRiskActionCell(record: RiskActionRecord) {
  const actions = getRecordActions(record)
  if (!actions.length) return '—'
  return h('div', { class: 'risk-action-tags' },
    actions.map(action => h('span', { class: `risk-action-tag ${action.toLowerCase()}` }, action)))
}

function renderRiskEventTime(record: RiskActionRecord) {
  const range = record.eventTimeRange
  if (range && (range.start || range.end)) {
    const startText = formatDate(range.start ?? null)
    const endText = formatDate(range.end ?? null)
    if (startText === endText) {
      return startText
    }
    return `${startText} - ${endText}`
  }
  return formatDate(record.eventTime ?? null)
}

function renderFlag(value: boolean | null | undefined, onLabel = '已触发', offLabel = '未触发') {
  if (value == null) return '—'
  const cls = value ? 'risk-flag on' : 'risk-flag off'
  return h('span', { class: cls }, value ? onLabel : offLabel)
}

function formatRiskEventType(action: string | null, classification?: string | null) {
  if (!action) return '风控模型动作'
  const upper = action.toUpperCase()
  const cls = classification ? formatClassification(classification) : ''
  return cls ? `风控动作 ${upper} ｜ ${cls}` : `风控动作 ${upper}`
}

function normalizeDetails(raw: any) {
  if (!raw) return {}
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw)
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch (err) {
      return {}
    }
  }
  return raw
}

function formatClassification(value: string | null) {
  if (!value) return '—'
  const upper = value.toUpperCase() as Classification
  return classificationMeta[upper]?.label || value
}

function formatScoreValue(value: number | null | undefined): string {
  if (value == null || Number.isNaN(Number(value))) {
    return '0'
  }
  const fixed = Number(value).toFixed(1)
  return fixed.endsWith('.0') ? fixed.slice(0, -2) : fixed
}

function formatMultiplier(value: number): string {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric === 0) {
    return '×1'
  }
  const formatted = numeric % 1 === 0 ? numeric.toFixed(1) : numeric.toFixed(2)
  return `×${formatted.replace(/0+$/g, '').replace(/\.$/, '')}`
}

function formatIsoDuration(value: any): string {
  if (typeof value !== 'string' || !value.startsWith('P')) return ''
  const upper = value.toUpperCase()
  const timeIndex = upper.indexOf('T')
  const dateSection = timeIndex === -1 ? upper.slice(1) : upper.slice(1, timeIndex)
  const timeSection = timeIndex === -1 ? '' : upper.slice(timeIndex + 1)
  const parts: string[] = []

  const dateMap: Record<string, string> = { Y: '年', M: '个月', W: '周', D: '天' }
  const timeMap: Record<string, string> = { H: '小时', M: '分钟', S: '秒' }
  const numberPattern = /(\d+(?:\.\d+)?)([A-Z])/g
  let match
  if (dateSection) {
    while ((match = numberPattern.exec(dateSection)) !== null) {
      const [, num, unit] = match
      const label = dateMap[unit]
      if (label) {
        const formatted = Number(num)
        parts.push(`${formatted % 1 === 0 ? formatted.toString().replace(/\.0$/, '') : num} ${label}`)
      }
    }
  }
  if (timeSection) {
    while ((match = numberPattern.exec(timeSection)) !== null) {
      const [, num, unit] = match
      const label = timeMap[unit]
      if (label) {
        const formatted = Number(num)
        parts.push(`${formatted % 1 === 0 ? formatted.toString().replace(/\.0$/, '') : num} ${label}`)
      }
    }
  }
  return parts.join(' ') || value
}

function summarizeMetrics(metrics: any): string | null {
  if (!metrics || typeof metrics !== 'object') return null
  if (Array.isArray(metrics.newDevices) && metrics.newDevices.length) {
    return `新设备：${metrics.newDevices.join(', ')}`
  }
  if (Array.isArray(metrics.devices) && metrics.devices.length) {
    return `涉事设备：${metrics.devices.join(', ')}`
  }
  if (typeof metrics.events === 'number') {
    return `事件数：${metrics.events}`
  }
  if (typeof metrics.count === 'number') {
    return `计数：${metrics.count}`
  }
  return null
}

function extractScoreSummary(item: RiskActionRecord) {
  const raw: any = item.details?.scores
  if (!raw || typeof raw !== 'object') {
    return null
  }
  const base = Number(raw.base ?? raw.baseScore ?? 0)
  const afterMulti = Number(raw.afterMultiSource ?? raw.afterMulti ?? base)
  const multiSourceApplied = Boolean(raw.multiSourceApplied)
  const multiSourceMultiplier = multiSourceApplied ? 1.2 : afterMulti === base ? 1.0 : afterMulti / (base || 1)
  const timeMultiplier = Number(raw.timeMultiplier ?? 1)
  const total = Number(raw.total ?? raw.totalScore ?? item.score ?? afterMulti * timeMultiplier)
  return { base, multiSourceMultiplier, afterMulti, timeMultiplier, total }
}

function extractFRuleBreakdown(item: RiskActionRecord) {
  const details: any = item.details
  const fRules = Array.isArray(details?.fRules) ? details.fRules : []
  return fRules
    .filter((rule: any) => rule?.triggered)
    .map((rule: any, index: number) => {
      const id = typeof rule?.id === 'string' && rule.id ? rule.id : `rule-${index}`
      const name = typeof rule?.definition?.name === 'string' && rule.definition.name ? rule.definition.name : id
      const reason = typeof rule?.reason === 'string' && rule.reason ? rule.reason : name
      const occurrences = Number(rule?.occurrences ?? 0)
      const occText = occurrences > 1 ? `触发 ${occurrences} 次` : null
      const duration = formatIsoDuration(rule?.duration)
      const durationText = duration ? `持续时长：${duration}` : null
      const score = typeof rule?.score === 'number' ? Number(rule.score) : null
      const scoreText = score !== null ? `得分 +${formatScoreValue(score)} 分` : null
      const contributions = Array.isArray(rule?.contributions) ? rule.contributions : []
      const contributionRows =
        contributions.length > 0
          ? contributions.map((entry: any, idx: number) => {
              const value = typeof entry?.value === 'number' ? Number(entry.value) : null
              const desc = typeof entry?.description === 'string' && entry.description ? entry.description : ''
              const scoreLabel = value !== null ? `${formatScoreValue(value)} 分` : ''
              return { id: `${id}-contrib-${idx}`, text: desc ? `${scoreLabel}（${desc}）` : scoreLabel }
            })
          : []
      const metricsText = summarizeMetrics(rule?.metrics)
      const baseParts = [reason, occText, metricsText, durationText].filter(Boolean)
      return {
        id,
        text: baseParts.join(' ｜ '),
        score: scoreText,
        contributions: contributionRows,
      }
    })
}

function renderRiskDetail(record: RiskActionRecord) {
  const scoreSummary = extractScoreSummary(record)
  const rules = extractFRuleBreakdown(record)
  const headParts = [
    `综合得分 ${formatScoreValue(scoreSummary?.total ?? record.score ?? 0)}`,
    record.classification ? `→ ${formatClassification(record.classification)}` : '',
    record.summary || ''
  ].filter(Boolean)

  const header = h('div', { class: 'risk-detail-head' }, headParts.join(' ｜ '))

  const scoreBody = scoreSummary
    ? h('div', { class: 'risk-score-grid' }, [
        ['基础得分', formatScoreValue(scoreSummary.base)],
        ['协同系数', formatMultiplier(scoreSummary.multiSourceMultiplier)],
        ['协同后', formatScoreValue(scoreSummary.afterMulti)],
        ['昼夜系数', formatMultiplier(scoreSummary.timeMultiplier)],
        ['综合得分', formatScoreValue(scoreSummary.total)]
      ].map(([label, value]) => h('div', { class: 'score-line' }, [
        h('span', { class: 'score-label' }, label as string),
        h('span', { class: 'score-value' }, value as string)
      ])))
    : null

  const ruleNodes = rules.length
    ? h('div', { class: 'risk-rules' }, rules.map(rule => h('div', { class: 'rule-row', key: rule.id }, [
        h('div', { class: 'rule-main' }, rule.text),
        rule.score ? h('div', { class: 'rule-score' }, rule.score) : null,
        rule.contributions && rule.contributions.length
          ? h('div', { class: 'rule-contrib' }, rule.contributions.map((c: any) => h('div', { class: 'contrib-row', key: c.id }, c.text)))
          : null
      ])))
    : null

  return h('div', { class: 'risk-detail-cell' }, [header, scoreBody, ruleNodes].filter(Boolean))
}
</script>

<template>
  <div class="event-center">
    <a-tabs v-model:activeKey="activeKey" type="card" @change="onTabChange">
      <a-tab-pane key="risk" tab="风控告警">
        <div class="table-toolbar">
          <a-checkbox v-model:checked="showA1">显示 A1 取证</a-checkbox>
        </div>
        <a-table
          class="risk-table"
          row-key="id"
          :columns="riskColumns"
          :data-source="filteredRiskActions"
          :loading="loading.risk"
          :pagination="pagination"
          :scroll="riskTableScroll"
          table-layout="fixed"
        />
      </a-tab-pane>
      <a-tab-pane key="alerts" tab="所有告警事件">
        <a-table
          row-key="id"
          :columns="alertColumns"
          :data-source="alerts"
          :loading="loading.alerts"
          :pagination="pagination"
          :scroll="tableScroll"
        />
      </a-tab-pane>
      <a-tab-pane key="camera" tab="摄像头告警">
        <a-table
          row-key="id"
          :columns="alertColumns"
          :data-source="camera"
          :loading="loading.camera"
          :pagination="pagination"
          :scroll="tableScroll"
        />
      </a-tab-pane>
      <a-tab-pane key="radar" tab="雷达告警">
        <a-table
          row-key="id"
          :columns="radarColumns"
          :data-source="radar"
          :loading="loading.radar"
          :pagination="pagination"
          :scroll="tableScroll"
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

.risk-detail-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
  line-height: 1.4;
}

.risk-table :deep(.ant-table) {
  table-layout: fixed;
  width: 100%;
}

.risk-table :deep(.ant-table-cell) {
  white-space: normal;
  word-break: break-word;
}

.table-toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}

.risk-detail-head {
  font-weight: 600;
  color: #1f2937;
}

.risk-action-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.risk-action-tag {
  padding: 2px 6px;
  border-radius: 4px;
  border: 1px solid rgba(15, 23, 42, 0.2);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}

.risk-action-tag.a1 {
  background: rgba(148, 163, 184, 0.2);
  color: #475569;
  border-color: rgba(148, 163, 184, 0.5);
}

.risk-action-tag.a2 {
  background: rgba(59, 130, 246, 0.15);
  color: #1d4ed8;
  border-color: rgba(37, 99, 235, 0.35);
}

.risk-action-tag.a3 {
  background: rgba(239, 68, 68, 0.15);
  color: #dc2626;
  border-color: rgba(220, 38, 38, 0.35);
}

.risk-flag {
  font-weight: 600;
  font-size: 13px;
}

.risk-flag.on {
  color: #dc2626;
}

.risk-flag.off {
  color: #64748b;
}

.risk-score-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 4px 12px;
  background: #f7f9fc;
  padding: 8px 10px;
  border-radius: 6px;
  border: 1px solid rgba(0, 0, 0, 0.04);
}

.score-line {
  display: flex;
  justify-content: space-between;
  color: #334155;
  font-size: 13px;
}

.score-label {
  color: #475569;
}

.score-value {
  font-weight: 600;
}

.risk-rules {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.rule-row {
  padding: 6px 8px;
  border-radius: 6px;
  background: #fafafa;
  border: 1px solid rgba(0, 0, 0, 0.04);
}

.rule-main {
  color: #0f172a;
  font-weight: 500;
  margin-bottom: 2px;
}

.rule-score {
  color: #e11d48;
  font-weight: 600;
  font-size: 13px;
}

.rule-contrib {
  margin-top: 4px;
  color: #475569;
  font-size: 12px;
}

.contrib-row + .contrib-row {
  margin-top: 2px;
}
</style>
