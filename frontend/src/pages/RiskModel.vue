<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'

type Classification = 'P1' | 'P2' | 'P3' | 'P4' | 'INFO'
type RiskAssessment = {
  id: number
  classification: Classification
  score: number | null
  summary: string | null
  windowStart: string | null
  windowEnd: string | null
  updatedAt: string | null
  details: Record<string, unknown> | null
  state: RiskState | null
}

type RiskState = 'IDLE' | 'MONITORING' | 'CHALLENGE' | 'DISPATCHED' | 'RESOLVED' | string

type ScenarioButton = {
  id: string
  name: string
  description: string
  group: string
}

type ScenarioGroup = {
  name: string
  buttons: ScenarioButton[]
}

const REFRESH_INTERVAL = 15000
const loading = ref(false)
const assessments = ref<RiskAssessment[]>([])
const errorMessage = ref<string | null>(null)
const pollingTimer = ref<number | null>(null)
const scenarioLoading = ref<string | null>(null)
const resetLoading = ref(false)

const scenarioButtons: ScenarioButton[] = [
  {
    id: 'F1-BASE',
    name: 'F1 一般区人形出现',
    description: '注入一次普通防区的人形检测，验证 F1 触发 P3 并执行 A1。',
    group: 'F规则验证',
  },
  {
    id: 'F2-FIRST',
    name: 'F2 未知 IMSI 首现',
    description: '创建一个非白名单 IMSI 首次出现的记录，验证 5 分钟内只触发一次。',
    group: 'F规则验证',
  },
  {
    id: 'F3-REAPPEAR',
    name: 'F3 IMSI 再现/久留',
    description: '模拟同一设备在 30 分钟内再现并由摄像头佐证，验证升级至 P2 并触发 A2。',
    group: 'F规则验证',
  },
  {
    id: 'F4-CORELINE',
    name: 'F4 虚拟警戒线越界',
    description: '注入核心区越界数据，验证直接评为 P1 并进入 G1 出警路径。',
    group: 'F规则验证',
  },
  {
    id: 'A1-ONLY',
    name: 'A1 静默记录闭环',
    description: '构造弱证据事件，仅触发 A1，确认不会误升至 A2/A3。',
    group: 'A动作路径',
  },
  {
    id: 'A2-SUCCEED',
    name: 'A2 挑战有效',
    description: '模拟 A2 后目标离场的情形，验证事件在挑战窗口内收束。',
    group: 'A动作路径',
  },
  {
    id: 'A2-FAIL-G2',
    name: 'A2 挑战无效→G2',
    description: '目标无视远程警告持续存在，验证 G2 在挑战窗口后触发 A3。',
    group: 'A动作路径',
  },
  {
    id: 'G1-P1-A3',
    name: 'G1 P1 即刻出警',
    description: '通过核心区越界与外围佐证，验证 P1 优先触发 G1 且忽略低级规则。',
    group: 'G派警逻辑',
  },
  {
    id: 'G2-CHALLENGE',
    name: 'G2 挑战窗口对齐',
    description: '在挑战窗口内外分别注入 IMSI，再现挑战无效升级与新事件分界。',
    group: 'G派警逻辑',
  },
  {
    id: 'G3-REPEAT',
    name: 'G3 重复侵扰巡查',
    description: '注入 24 小时内多次 P2/P3 事件，验证触发预防性巡查。',
    group: 'G派警逻辑',
  },
  {
    id: 'FX-MERGE-UP',
    name: '融合多源上调',
    description: '联合摄像头、IMSI、雷达注入，检查融合引擎合并并提升优先级。',
    group: '融合与状态机',
  },
  {
    id: 'SM-ONE-A3',
    name: '状态机单次出警',
    description: '模拟同一事件多次触发出警条件，验证状态机仅执行一次 A3。',
    group: '融合与状态机',
  },
  {
    id: 'SM-CLOSE',
    name: '状态机干净收尾',
    description: '构造目标离场且双倍挑战窗无再现的记录，验证事件转入已恢复状态。',
    group: '融合与状态机',
  },
  {
    id: 'NEW-INCIDENT',
    name: '挑战窗后新事件',
    description: '在挑战窗口结束后重新注入同一 IMSI，验证被视为全新事件。',
    group: '融合与状态机',
  },
  {
    id: 'SYNC-T-REID',
    name: '挑战窗口同步',
    description: '制造 T−ε 与 T+ε 两种再识别，验证挑战窗口与再识别窗口完全一致。',
    group: '同步与冷却',
  },
  {
    id: 'CD-F1',
    name: 'F1 30 秒冷却',
    description: '在 30 秒内多次注入同一目标 F1，验证冷却抑制重复触发。',
    group: '同步与冷却',
  },
  {
    id: 'CD-F2',
    name: 'F2 5 分钟冷却',
    description: '对同一 IMSI 在 5 分钟内重复注入，验证只记录第一次出现。',
    group: '同步与冷却',
  },
  {
    id: 'CD-F4',
    name: 'F4 越界防抖',
    description: '连续越界抖动的模拟数据，验证一次事件只触发一次 F4/G1。',
    group: '同步与冷却',
  },
]

const scenarioGroups = computed<ScenarioGroup[]>(() => {
  const groups: ScenarioGroup[] = []
  const lookup = new Map<string, ScenarioGroup>()
  for (const button of scenarioButtons) {
    let group = lookup.get(button.group)
    if (!group) {
      group = { name: button.group, buttons: [] }
      lookup.set(button.group, group)
      groups.push(group)
    }
    group.buttons.push(button)
  }
  return groups
})

const classificationOrder: Classification[] = ['P1', 'P2', 'P3', 'P4']
const classificationMeta: Record<Classification, { label: string; tag: string; empty: string; description: string }> = {
  P1: {
    label: 'P1 最高优先级',
    tag: 'error',
    empty: '暂无 P1 事件',
    description: '确认的重大入侵威胁（核心区越界或多传感器同时确认）。',
  },
  P2: {
    label: 'P2 高优先级',
    tag: 'warning',
    empty: '暂无 P2 事件',
    description: '严重怀疑的入侵威胁（持续停留或挑战失败）。',
  },
  P3: {
    label: 'P3 中等优先级',
    tag: 'processing',
    empty: '暂无 P3 事件',
    description: '一般可疑事件（外围闯入或未知设备）。',
  },
  P4: {
    label: 'P4 低优先级',
    tag: 'success',
    empty: '暂无 P4 事件',
    description: '轻微异常或系统试探，仅记录观察。',
  },
  INFO: {
    label: '信息留存',
    tag: 'default',
    empty: '暂无',
    description: '后备类别，用于兼容旧版数据。',
  },
}

const stateMeta: Record<string, { label: string; color: string; description: string }> = {
  IDLE: {
    label: '空闲',
    color: 'default',
    description: '站点未检测到威胁，维持空闲状态。',
  },
  MONITORING: {
    label: '警戒激活',
    color: 'processing',
    description: '存在可疑信号，正在记录并观察。',
  },
  CHALLENGE: {
    label: '远程挑战',
    color: 'warning',
    description: '已执行远程喊话，等待挑战窗口反馈。',
  },
  DISPATCHED: {
    label: '出警处理中',
    color: 'error',
    description: '满足 G 规则条件，已派警处理。',
  },
  RESOLVED: {
    label: '已恢复',
    color: 'success',
    description: '事件处理完毕，进入冷却阶段。',
  },
}

const groupedAssessments = computed(() => {
  const base: Record<string, RiskAssessment[]> = {}
  Object.keys(classificationMeta).forEach((key) => {
    base[key] = []
  })
  for (const item of assessments.value) {
    const key = classificationMeta[item.classification] ? item.classification : 'INFO'
    base[key].push(item)
  }
  return base
})

const groupedPages = computed(() => {
  const output: Record<string, RiskAssessment[][]> = {}
  Object.entries(groupedAssessments.value).forEach(([key, list]) => {
    const pages: RiskAssessment[][] = []
    for (let i = 0; i < list.length; i += 10) {
      pages.push(list.slice(i, i + 10))
    }
    output[key] = pages.length ? pages : [[]]
  })
  return output
})

const paginationState = ref<Record<string, number>>({})
const currentPage = (key: string) => paginationState.value[key] ?? 0
const currentPageData = (key: string) => {
  const pages = groupedPages.value[key] ?? [[]]
  const index = Math.min(currentPage(key), pages.length - 1)
  return pages[index] ?? []
}
const setPage = (key: string, page: number) => {
  paginationState.value = { ...paginationState.value, [key]: page }
}

const logAssessments = computed(() => groupedAssessments.value.INFO ?? [])
const boardHasData = computed(() =>
  classificationOrder.some((key) => (groupedAssessments.value[key]?.length ?? 0) > 0)
)

const latestAssessment = computed(() => {
  if (!assessments.value.length) return null
  const sorted = [...assessments.value].sort((a, b) => {
    const left = a.updatedAt ?? a.windowEnd ?? ''
    const right = b.updatedAt ?? b.windowEnd ?? ''
    return right.localeCompare(left)
  })
  return sorted[0] ?? null
})

const currentState = computed<RiskState | null>(() => latestAssessment.value?.state ?? null)

function toClassification(value: any): Classification {
  const text = typeof value === 'string' ? value.toUpperCase() : ''
  if (classificationMeta[text as Classification]) {
    return text as Classification
  }
  return 'INFO'
}

function normalizeAssessment(raw: any): RiskAssessment {
  const classification = toClassification(raw?.classification)
  const details =
    raw?.details && typeof raw.details === 'object'
      ? (raw.details as Record<string, unknown>)
      : null
  return {
    id: Number(raw?.id ?? 0),
    classification,
    score: raw?.score != null ? Number(raw.score) : null,
    summary: raw?.summary != null ? String(raw.summary) : null,
    windowStart: raw?.windowStart ?? null,
    windowEnd: raw?.windowEnd ?? null,
    updatedAt: raw?.updatedAt ?? raw?.windowEnd ?? null,
    details,
    state: extractState(details),
  }
}

function extractState(details: Record<string, unknown> | null): RiskState | null {
  if (!details) return null
  const stateMachine = details.stateMachine as Record<string, unknown> | undefined
  const current = typeof stateMachine?.current === 'string' ? stateMachine.current.trim() : ''
  return current ? (current as RiskState) : null
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

function formatPriority(item: RiskAssessment): string {
  const meta = classificationMeta[item.classification]
  return meta ? meta.label : item.classification
}

function formatStateLabel(state: RiskState | null): string {
  if (!state) return '状态未知'
  const meta = stateMeta[state]
  return meta ? meta.label : state
}

function stateTagColor(state: RiskState | null): string {
  return state && stateMeta[state] ? stateMeta[state].color : 'default'
}

function stateDescription(state: RiskState | null): string {
  if (!state) return '—'
  const meta = stateMeta[state]
  return meta ? meta.description : `当前状态：${state}`
}

function formatUpdatedLabel(item: RiskAssessment): string {
  if (!item.updatedAt) return '更新时间：—'
  return `更新时间：${formatTime(item.updatedAt)}`
}

function formatIsoDuration(value: any): string {
  if (typeof value !== 'string' || !value.startsWith('P')) return ''
  const match = value.match(/^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/)
  if (!match) return value
  const [, d, h, m, s] = match
  const parts: string[] = []
  if (d) parts.push(`${Number(d)} 天`)
  if (h) parts.push(`${Number(h)} 小时`)
  if (m) parts.push(`${Number(m)} 分钟`)
  if (s) parts.push(`${Number(s)} 秒`)
  return parts.join('') || value
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

function extractFRuleBreakdown(item: RiskAssessment): Array<{ id: string; text: string }> {
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
      const durationText = duration ? `持续 ${duration}` : null
      const metricsText = summarizeMetrics(rule?.metrics)
      const parts = [reason, occText, metricsText, durationText].filter(Boolean)
      return { id, text: parts.join(' ｜ ') }
    })
}

function extractFlags(item: RiskAssessment): Array<{ label: string; color: string }> {
  const details: any = item.details
  const flags: Array<{ label: string; color: string }> = []
  const fRules = Array.isArray(details?.fRules) ? details.fRules : []
  const actions = Array.isArray(details?.actions) ? details.actions : []
  const gRules = Array.isArray(details?.gRules) ? details.gRules : []
  const has = (predicate: (entry: any) => boolean) => fRules.some(predicate)
  const seen = new Set<string>()
  if (gRules.some((rule: any) => rule?.triggered)) {
    seen.add('DISPATCH')
    flags.push({ label: '派警执行', color: 'error' })
  }
  if (actions.some((action: any) => action?.id === 'A2' && action.triggered)) {
    seen.add('A2')
    flags.push({ label: '远程挑战', color: 'orange' })
  }
  if (has((rule: any) => rule?.id === 'F4' && rule.triggered) && !seen.has('F4')) {
    seen.add('F4')
    flags.push({ label: '核心越界', color: 'magenta' })
  }
  if (has((rule: any) => rule?.id === 'F3' && rule.triggered) && !seen.has('F3')) {
    seen.add('F3')
    flags.push({ label: 'IMSI 重返', color: 'volcano' })
  }
  if (has((rule: any) => rule?.id === 'F2' && rule.triggered) && !seen.has('F2')) {
    seen.add('F2')
    flags.push({ label: '未知 IMSI', color: 'geekblue' })
  }
  if (has((rule: any) => rule?.id === 'F1' && rule.triggered) && !seen.has('F1')) {
    seen.add('F1')
    flags.push({ label: '外围闯入', color: 'cyan' })
  }
  return flags
}

function extractActionSummaries(item: RiskAssessment): Array<{ id: string; text: string }> {
  const details: any = item.details
  const actions = Array.isArray(details?.actions) ? details.actions : []
  return actions.map((action: any, index: number) => {
    const id = typeof action?.id === 'string' && action.id ? action.id : `action-${index}`
    const name = typeof action?.definition?.name === 'string' ? action.definition.name : id
    const status = action?.triggered ? '已执行' : action?.recommended ? '建议执行' : '不执行'
    const rationale = typeof action?.rationale === 'string' && action.rationale ? action.rationale : ''
    const parts = [name, status, rationale].filter(Boolean)
    return { id, text: parts.join(' ｜ ') }
  })
}

async function fetchAssessments(showToast = false) {
  const shouldSpin = assessments.value.length === 0 && !loading.value
  if (shouldSpin) {
    loading.value = true
  }
  try {
    const resp = await fetch('/api/risk/assessments?limit=150', { cache: 'no-store' })
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`)
    }
    const data = await resp.json()
    if (!Array.isArray(data)) {
      throw new Error('返回格式异常')
    }
    assessments.value = data.map(normalizeAssessment)
    errorMessage.value = null
  } catch (err: any) {
    const msg = err?.message || String(err)
    errorMessage.value = msg
    if (showToast) {
      message.error(`风控评估加载失败：${msg}`)
    }
  } finally {
    if (shouldSpin) {
      loading.value = false
    }
  }
}

async function triggerScenario(button: ScenarioButton) {
  if (scenarioLoading.value) {
    return
  }
  scenarioLoading.value = button.id
  try {
    const resp = await fetch(`/api/risk/scenarios/${encodeURIComponent(button.id)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    })
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`)
    }
    let data: any = null
    try {
      data = await resp.json()
    } catch (err) {
      console.debug('Failed to parse scenario response', err)
    }
    const tip = typeof data?.message === 'string' && data.message ? data.message : `${button.name} 场景已注入`
    message.success(tip)
    await fetchAssessments(true)
  } catch (err: any) {
    const msg = err?.message ?? String(err)
    message.error(`触发场景失败：${msg}`)
  } finally {
    scenarioLoading.value = null
  }
}

async function resetModelData() {
  if (resetLoading.value) {
    return
  }
  resetLoading.value = true
  try {
    const resp = await fetch('/api/risk/scenarios/cleanup', {
      method: 'POST',
      cache: 'no-store',
      headers: { 'Content-Type': 'application/json' },
    })
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`)
    }
    let data: any = null
    try {
      data = await resp.json()
    } catch (err) {
      console.debug('Failed to parse cleanup response', err)
    }
    const tip =
      typeof data?.message === 'string' && data.message
        ? data.message
        : '已清空风控模型数据'
    message.success(tip)
    assessments.value = []
    await fetchAssessments(true)
  } catch (err: any) {
    const msg = err?.message ?? String(err)
    message.error(`清除数据失败：${msg}`)
  } finally {
    resetLoading.value = false
  }
}

onMounted(() => {
  void fetchAssessments(true)
  pollingTimer.value = window.setInterval(() => {
    void fetchAssessments(false)
  }, REFRESH_INTERVAL)
})

onUnmounted(() => {
  if (pollingTimer.value !== null) {
    window.clearInterval(pollingTimer.value)
    pollingTimer.value = null
  }
})

const overviewHighlights = [
  {
    title: '统一时序',
    detail: '挑战窗口、IMSI 再识别周期与派警规则统一，保证远程喊话与再入判定连贯。',
  },
  {
    title: '动作解耦',
    detail: 'A1/A2/A3 分层响应独立于等级，可按实时威胁自主组合。',
  },
  {
    title: '规则可配置',
    detail: 'F/G 规则、阈值与时间窗集中在 risk-model.yml，可热调优。',
  },
  {
    title: '状态闭环',
    detail: '站点状态机覆盖监测、挑战、出警、结束全流程，避免事件被冷却遗忘。',
  },
  {
    title: '多源融合',
    detail: 'IMSI、视频、雷达在事件级别融合，提升可信度并抑制误报。',
  },
]

const priorityCards = [
  {
    id: 'P1',
    title: 'P1 最高优先级',
    description: '核心区越界或多传感器确认的重大入侵，直接进入 A2 + G1→A3。',
  },
  {
    id: 'P2',
    title: 'P2 高优先级',
    description: '持续停留、重返或挑战失败的高危事件，执行 A2，必要时 G2 派警。',
  },
  {
    id: 'P3',
    title: 'P3 中等优先级',
    description: '外围闯入或未知设备出现，触发 A1 监控并持续评估。',
  },
  {
    id: 'P4',
    title: 'P4 低优先级',
    description: '轻微异常或试探事件，仅留痕监控。',
  },
]

const actionCards = [
  {
    id: 'A1',
    title: 'A1 监控记录',
    detail: '静默录像、标记事件、增强跟踪，为后续处置积累证据。',
  },
  {
    id: 'A2',
    title: 'A2 远程交互',
    detail: '语音警告、闪灯或警号，挑战入侵者并观察 5 分钟窗口。',
  },
  {
    id: 'A3',
    title: 'A3 人力响应',
    detail: '通知安保/警方现场处置，G1/G2/G3 任一命中即触发。',
  },
]

const fRuleColumns = [
  { title: '规则', dataIndex: 'id', key: 'id', width: 80 },
  { title: '触发条件', dataIndex: 'trigger', key: 'trigger' },
  { title: '数据源', dataIndex: 'source', key: 'source', width: 160 },
  { title: '时间窗', dataIndex: 'window', key: 'window', width: 160 },
  { title: '频率限制', dataIndex: 'limit', key: 'limit', width: 200 },
  { title: '冷却', dataIndex: 'cooldown', key: 'cooldown', width: 120 },
  { title: '动作影响', dataIndex: 'impact', key: 'impact', width: 200 },
]

const fRuleRows = [
  {
    id: 'F1',
    trigger: '摄像头检测一般保护区人形闯入',
    source: '视频监控（虚拟警戒线/入侵）',
    window: '即时触发，单目标追踪 30s',
    limit: '同一摄像头 30s 内至多 1 次',
    cooldown: '30s',
    impact: '触发 A1，事件优先级 ≥ P3',
  },
  {
    id: 'F2',
    trigger: 'IMSI 探针捕获非白名单设备首次出现',
    source: 'IMSI 探针',
    window: '即时触发，同一设备 5 分钟去重',
    limit: '同一 IMEI/IMSI 首次出现 1 次',
    cooldown: '5min',
    impact: '触发 A1，提升至 P3/P4',
  },
  {
    id: 'F3',
    trigger: 'IMSI 持续停留 ≥10min 或 30min 内重返',
    source: 'IMSI + 历史会话',
    window: '30min 分析窗',
    limit: '同一事件周期 1 次',
    cooldown: '30min',
    impact: '提升至 P2，建议执行 A2',
  },
  {
    id: 'F4',
    trigger: '跨越虚拟警戒线进入核心区',
    source: '视频警戒线 / 周界传感器',
    window: '即时触发',
    limit: '每次越界 1 次',
    cooldown: '1min',
    impact: '立即升至 P1，执行 A2 并触发 G1',
  },
]

const gRuleColumns = [
  { title: '规则', dataIndex: 'id', key: 'id', width: 80 },
  { title: '触发条件', dataIndex: 'trigger', key: 'trigger' },
  { title: '动作', dataIndex: 'action', key: 'action', width: 120 },
  { title: '说明', dataIndex: 'note', key: 'note' },
]

const gRuleRows = [
  {
    id: 'G1',
    trigger: '事件优先级达到 P1',
    action: 'A3',
    note: '重大入侵立即派警，忽略低优先级规则。',
  },
  {
    id: 'G2',
    trigger: 'A2 执行后挑战窗口（5min）仍未解除',
    action: 'A3',
    note: '警告无效立即升级为出警。',
  },
  {
    id: 'G3',
    trigger: '24h 内同站点多次触发 P2/P1',
    action: 'A3',
    note: '持续试探或踩点触发巡查。',
  },
]

const stateMachineSteps = [
  { state: 'IDLE', description: '空闲：站点无事件。' },
  { state: 'MONITORING', description: '警戒激活：任一 F 规则触发，执行 A1。' },
  { state: 'CHALLENGE', description: '远程挑战：A2 执行后等待挑战窗口。' },
  { state: 'DISPATCHED', description: '出警处理中：G1/G2/G3 任一命中，执行 A3。' },
  { state: 'RESOLVED', description: '事件结束：威胁解除或出警完成，进入冷却。' },
]

const advantageList = [
  'P1–P4 多因子优先级替代旧版 E1–E3，准确反映威胁强度。',
  'A1/A2/A3 响应解耦，远程喊话与现场派警各自可控。',
  '派警决策由 G1–G3 接管，规则互斥且优先级清晰，避免重复出警。',
  'F 规则标准化定义时间窗、冷却与数据源，易于工程落地。',
  '站点状态机与事件融合引擎让 IMSI、视频、雷达形成单一事件视角。',
  'YAML 参数集中管理，可结合现场反馈快速调优。',
]

const migrationSteps = [
  '导入 risk-model.yml 并校准站点参数（警戒线、挑战窗口、冷却时间等）。',
  '部署融合引擎与状态机模块，联调 IMSI / 摄像头 / 雷达数据接口。',
  '分阶段启用：先监测后处置，收集触发数据再逐步开放 A2/A3。',
  '培训值守人员理解 P1–P4 与 A1/A2/A3 的对应策略。',
  '上线后持续评估误报/漏报情况，通过配置迭代优化规则。',
]

const fusionHighlights = [
  'IMSI 再识别窗口与挑战窗口统一为 5 分钟，保证远程喊话结果可验证。',
  '事件详情包含 F 规则触发次数、持续时长、涉事设备等指标，便于复核。',
  '雷达数据可用于补强无光场景的发现能力，并在详情中展示触发计数。',
]

</script>

<template>
  <div class="risk-model">
    <header class="page-header">
      <h1>风控模型总览</h1>
      <p>古墓户外安防场景的 P1–P4 事件优先级、A1/A2/A3 响应体系与 F/G 规则重构方案。</p>
    </header>

    <section class="scenario-actions">
      <h2>虚拟场景注入</h2>
      <p class="section-note">点击下方按钮向数据库写入模拟数据，快速验证各类 F/P/A/G 流程。</p>
      <div class="scenario-toolbar">
        <a-popconfirm
          title="确认清空最近风控数据？"
          ok-text="确认"
          cancel-text="取消"
          :disabled="resetLoading"
          @confirm="resetModelData"
        >
          <a-button danger :loading="resetLoading">清空模型数据</a-button>
        </a-popconfirm>
      </div>
      <div class="scenario-groups">
        <div v-for="group in scenarioGroups" :key="group.name" class="scenario-group">
          <h3 class="scenario-group-title">{{ group.name }}</h3>
          <div class="scenario-grid">
            <a-card v-for="button in group.buttons" :key="button.id" class="scenario-card" :title="button.name">
              <p class="scenario-desc">{{ button.description }}</p>
              <a-button type="primary" block :loading="scenarioLoading === button.id" @click="triggerScenario(button)">
                触发场景
              </a-button>
            </a-card>
          </div>
        </div>
      </div>
    </section>

    <section class="live-section">
      <h2>实时风险态势</h2>
      <p class="section-note">基于最近 90 分钟内的 IMSI / 摄像头 / 雷达数据实时评分与名单判定。</p>
      <div v-if="currentState" class="state-banner">
        <span class="state-label">当前站点状态机</span>
        <a-tag :color="stateTagColor(currentState)">{{ formatStateLabel(currentState) }}</a-tag>
        <span class="state-description">{{ stateDescription(currentState) }}</span>
      </div>
      <a-spin :spinning="loading">
        <a-alert
          v-if="errorMessage"
          type="error"
          :message="`数据获取失败：${errorMessage}`"
          show-icon
          class="mb12"
        />
        <div v-if="boardHasData" class="risk-board">
          <div
            v-for="key in classificationOrder"
            :key="key"
            :class="['risk-column', `risk-column-${key.toLowerCase()}`]"
          >
            <div class="risk-column-header">
              <a-tag :color="classificationMeta[key].tag">{{ classificationMeta[key].label }}</a-tag>
              <span class="risk-count">{{ groupedAssessments[key].length }}</span>
            </div>
            <div class="risk-column-desc">{{ classificationMeta[key].description }}</div>
            <div class="risk-column-body">
              <a-empty
                v-if="!groupedAssessments[key].length"
                :description="classificationMeta[key].empty"
              />
              <a-list
                v-else
                :data-source="currentPageData(key)"
                :pagination="false"
                :split="false"
              >
                <template #renderItem="{ item }">
                  <a-list-item class="risk-list-item">
                    <div class="risk-item">
                      <div class="risk-item-header">
                        <div class="risk-item-context">
                          <span class="risk-item-state" :class="`state-${(item.state || 'unknown').toLowerCase()}`">
                            {{ formatStateLabel(item.state) }}
                          </span>
                          <span class="risk-item-updated">{{ formatUpdatedLabel(item) }}</span>
                        </div>
                        <a-tag :color="classificationMeta[item.classification]?.tag" class="risk-item-priority">
                          {{ formatPriority(item) }}
                        </a-tag>
                      </div>
                      <div v-if="item.summary" class="risk-item-summary">{{ item.summary }}</div>
                      <div v-if="extractFlags(item).length" class="risk-item-flags">
                        <a-tag
                          v-for="flag in extractFlags(item)"
                          :key="flag.label"
                          :color="flag.color"
                        >
                          {{ flag.label }}
                        </a-tag>
                      </div>
                      <div v-if="extractFRuleBreakdown(item).length" class="risk-item-breakdown">
                        <ul>
                          <li v-for="hit in extractFRuleBreakdown(item)" :key="hit.id">
                            <span class="rule-desc">{{ hit.text }}</span>
                          </li>
                        </ul>
                      </div>
                      <div v-if="extractActionSummaries(item).length" class="risk-item-actions">
                        <ul>
                          <li v-for="action in extractActionSummaries(item)" :key="action.id">
                            <span class="rule-desc">{{ action.text }}</span>
                          </li>
                        </ul>
                      </div>
                      <div class="risk-item-footer">
                        <span>更新时间：{{ formatTime(item.updatedAt) }}</span>
                        <span>窗口：{{ formatTime(item.windowStart) }} → {{ formatTime(item.windowEnd) }}</span>
                      </div>
                    </div>
                  </a-list-item>
                </template>
              </a-list>
              <a-pagination
                v-if="groupedPages[key] && groupedPages[key].length > 1"
                size="small"
                :page-size="10"
                :current="currentPage(key) + 1"
                :total="groupedAssessments[key].length"
                @change="(page) => setPage(key, page - 1)"
                style="margin-top:8px; text-align:right;"
              />
            </div>
          </div>
        </div>
        <a-empty v-else description="暂无风险事件" />
      </a-spin>
      <a-collapse v-if="logAssessments.length" :bordered="false" class="risk-log-collapse">
        <a-collapse-panel key="logs" header="低风险事件（仅记录）">
          <a-list
            size="small"
            :data-source="logAssessments"
            :pagination="false"
            :split="false"
          >
            <template #renderItem="{ item }">
              <a-list-item class="risk-log-item">
                <div class="risk-log-main">
                  <strong>{{ formatStateLabel(item.state) }}</strong>
                  <span v-if="item.summary" class="risk-log-summary">{{ item.summary }}</span>
                </div>
                <div class="risk-log-time">{{ formatTime(item.updatedAt) }}</div>
              </a-list-item>
            </template>
          </a-list>
        </a-collapse-panel>
      </a-collapse>
    </section>

    <section class="model-highlights">
      <h2>模型重构亮点</h2>
      <div class="card-grid repeated-block">
        <a-card v-for="item in overviewHighlights" :key="`highlight-${item.title}`" :title="item.title">
          <p>{{ item.detail }}</p>
        </a-card>
      </div>
    </section>

    <section>
      <h2>P1–P4 事件优先级</h2>
      <div class="card-grid priority-grid">
        <a-card v-for="card in priorityCards" :key="card.id" :title="card.title">
          <p>{{ card.description }}</p>
        </a-card>
      </div>
    </section>

    <section>
      <h2>A1/A2/A3 响应动作</h2>
      <div class="card-grid action-grid">
        <a-card v-for="card in actionCards" :key="card.id" :title="card.title">
          <p>{{ card.detail }}</p>
        </a-card>
      </div>
    </section>

    <section>
      <h2>F 规则标准化定义</h2>
      <a-table
        class="compact-table"
        size="small"
        :columns="fRuleColumns"
        :data-source="fRuleRows"
        :pagination="false"
        row-key="(record) => record.id"
      />
    </section>

    <section>
      <h2>G 规则派警决策引擎</h2>
      <a-table
        class="compact-table"
        size="small"
        :columns="gRuleColumns"
        :data-source="gRuleRows"
        :pagination="false"
        row-key="(record) => record.id"
      />
    </section>

    <section>
      <h2>站点状态机</h2>
      <ol class="timeline-list">
        <li v-for="step in stateMachineSteps" :key="step.state"><strong>{{ step.state }}</strong>：{{ step.description }}</li>
      </ol>
    </section>

    <section>
      <h2>多源融合重点</h2>
      <ul class="bullet-list">
        <li v-for="item in fusionHighlights" :key="item">{{ item }}</li>
      </ul>
    </section>

    <section>
      <h2>主要变化与优势</h2>
      <ul class="bullet-list">
        <li v-for="item in advantageList" :key="item">{{ item }}</li>
      </ul>
    </section>

    <section>
      <h2>迁移实施建议</h2>
      <ol class="timeline-list">
        <li v-for="step in migrationSteps" :key="step">{{ step }}</li>
      </ol>
    </section>

  </div>
</template>


<style scoped>
.fusion-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
}

.repeated-block {
  margin-top: 16px;
}

.risk-model {
  padding: 24px;
  color: var(--text-color);
  background: rgba(12, 24, 36, 0.6);
  min-height: 100%;
  box-sizing: border-box;
}

.page-header {
  margin-bottom: 24px;
}

.page-header h1 {
  margin: 0;
  font-size: 26px;
  font-weight: 600;
  color: #fff;
}

.page-header p {
  margin-top: 8px;
  color: rgba(255, 255, 255, 0.75);
}

.scenario-actions .section-note {
  margin-top: 4px;
  color: rgba(255, 255, 255, 0.65);
}

.scenario-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.scenario-groups {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.scenario-group-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.85);
}

.scenario-grid {
  margin-top: 16px;
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
}

.scenario-card {
  background: rgba(10, 26, 40, 0.7);
  border: 1px solid rgba(64, 169, 255, 0.2);
}

.scenario-card :deep(.ant-card-head) {
  border-bottom-color: rgba(255, 255, 255, 0.12);
}

.scenario-desc {
  min-height: 48px;
  color: rgba(255, 255, 255, 0.75);
}

section {
  margin-bottom: 32px;
}

.live-section .section-note {
  margin-top: 0;
  color: rgba(255, 255, 255, 0.65);
}

.state-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  padding: 12px 16px;
  background: rgba(19, 47, 76, 0.45);
  border: 1px solid rgba(64, 169, 255, 0.2);
  border-radius: 8px;
}

.state-label {
  font-weight: 600;
  color: rgba(255, 255, 255, 0.85);
}

.state-description {
  color: rgba(255, 255, 255, 0.7);
}

.risk-board {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
  margin-top: 16px;
}

.risk-column {
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 220px;
}

.risk-column-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.risk-column-desc {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.65);
}

.risk-column-body {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.risk-column-body :deep(.ant-list-items) {
  display: grid;
  gap: 16px;
}

.risk-column-body :deep(.ant-list-item) {
  padding: 0;
}

.risk-column-body :deep(.ant-empty) {
  margin: 32px 0;
}

.risk-count {
  font-size: 22px;
  font-weight: 600;
}

.risk-list-item {
  border: none;
}

.risk-item {
  display: grid;
  gap: 8px;
}

.risk-item-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.risk-item-context {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  word-break: break-word;
}

.risk-item-state {
  font-weight: 600;
  color: var(--text-color);
  text-transform: none;
}

.risk-item-state.state-monitoring {
  color: #13c2c2;
}

.risk-item-state.state-challenge {
  color: #faad14;
}

.risk-item-state.state-dispatched {
  color: #ff4d4f;
}

.risk-item-state.state-resolved {
  color: #52c41a;
}

.risk-item-updated {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.65);
}

.risk-item-priority {
  font-weight: 600;
}

.risk-item-summary {
  color: rgba(255, 255, 255, 0.8);
}

.risk-item-flags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.risk-item-footer {
  display: grid;
  gap: 4px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
}

.risk-log-collapse {
  margin-top: 16px;
}

.risk-log-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border: none;
  padding: 8px 0;
}

.risk-log-main {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  color: #000;
}

.risk-log-summary {
  color: #000;
}

.risk-log-time {
  font-size: 12px;
  color: #000;
}

.mb12 {
  margin-bottom: 12px;
}

h2 {
  margin: 0 0 16px;
  font-size: 20px;
  font-weight: 600;
  color: #fff;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
}


.risk-item-breakdown {
  padding: 8px 10px;
  background: rgba(0, 0, 0, 0.28);
  border-radius: 6px;
  display: grid;
  gap: 6px;
}

.risk-item-breakdown ul {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 4px;
}

.risk-item-breakdown li {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.85);
}

.risk-item-breakdown .rule-desc,
.risk-item-actions .rule-desc {
  display: block;
  word-break: break-all;
}

.risk-item-actions {
  padding: 8px 10px;
  background: rgba(0, 0, 0, 0.18);
  border-radius: 6px;
}

.risk-item-actions ul {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 4px;
}

.risk-item-actions li {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.75);
}

.priority-grid .ant-card,
.action-grid .ant-card {
  background: rgba(0, 0, 0, 0.25);
}

.timeline-list {
  padding-left: 18px;
  display: grid;
  gap: 8px;
  color: rgba(255, 255, 255, 0.85);
}

.card-stack {
  display: grid;
  gap: 16px;
}

.intro-block {
  padding: 16px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.25);
  display: grid;
  gap: 12px;
  color: rgba(255, 255, 255, 0.85);
}

.intro-block p {
  margin: 0;
}

.bullet-list {
  padding-left: 18px;
  margin: 0;
  display: grid;
  gap: 8px;
}

.bullet-list li {
  line-height: 1.6;
}

.compact-table {
  margin-top: 16px;
  background: rgba(0, 0, 0, 0.2);
}

.nested-table {
  margin-top: 8px;
}

.card-note {
  margin-bottom: 8px;
  color: rgba(255, 255, 255, 0.75);
}

.mt16 {
  margin-top: 16px;
}

.section-note {
  margin-top: 12px;
  color: rgba(255, 255, 255, 0.75);
}


@media (max-width: 768px) {
  .risk-model {
    padding: 16px;
  }

  .page-header h1 {
    font-size: 22px;
  }
}
</style>
