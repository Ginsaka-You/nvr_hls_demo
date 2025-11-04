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
    id: 'A1',
    name: '夜间-核心人形直击（仅F3）',
    description: '夜间 22:00 核心摄像头单独识别人形，期望 90 分 / P1 并触发 A2。',
    group: '夜间入闸与评分验证',
  },
  {
    id: 'A2',
    name: '夜间-雷达≥10s且进入10–20m并逼近（仅F2）',
    description: '夜间 23:30 雷达持续 ≥10s 并进入 10–20m 近域逼近，期望 72 分 / P1 → A2。',
    group: '夜间入闸与评分验证',
  },
  {
    id: 'A3',
    name: '夜间-外圈2未知IMSI（仅F1）',
    description: '夜间 02:00 出现 2 个未知 IMSI，期望 30 分 / P3，仅执行 A1。',
    group: '夜间入闸与评分验证',
  },
  {
    id: 'A4',
    name: '夜间-F1→F2短暂+逼近（链路）',
    description: '夜间 01:10 F1 与 F2 在 3 分钟内联动且逼近，期望 50.4 分 / P2 → A2。',
    group: '夜间入闸与评分验证',
  },
  {
    id: 'A5',
    name: '夜间-雷达仅持续=11s',
    description: '夜间 21:40 雷达仅满足持续 ≥10s，期望 30 分 / P3，仅取证。',
    group: '夜间入闸与评分验证',
  },
  {
    id: 'A6',
    name: '夜间-雷达≥10s且进入10–20m',
    description: '夜间 23:05 雷达持续并进入 10–20m 近域，期望 60 分 / P2 → A2。',
    group: '夜间入闸与评分验证',
  },
  {
    id: 'A7',
    name: '夜间-三源协同（F1×2+F2≥10s+F3）',
    description: '夜间 00:20 三源同时成立，期望 194.4 分 / P1 → A2。',
    group: '夜间入闸与评分验证',
  },
  {
    id: 'A8',
    name: '夜间-雷达短暂单点',
    description: '夜间 03:00 雷达短暂单点，期望 15 分 / P4，仅 A1。',
    group: '夜间入闸与评分验证',
  },
  {
    id: 'A9',
    name: '夜间-同IMSI重现（30min内）',
    description: '夜间 04:10 同一 IMSI 30 分钟内再现，期望 27 分 / P3，仅取证。',
    group: '夜间入闸与评分验证',
  },
  {
    id: 'A10',
    name: '夜间-白名单在场+核心人形',
    description: '夜间 22:45 核心见人且白名单设备在场，期望 90 分 / P1 → A2。',
    group: '夜间入闸与评分验证',
  },
  {
    id: 'B1',
    name: 'A2后等待窗到仍见核心人形（A3验证）',
    description: '夜间远程警报等待窗已满且核心仍有人形，期望触发 G2 → A3=是。',
    group: '远程警报等待窗 / A3 校验',
  },
  {
    id: 'B2',
    name: 'A2后等待窗到雷达仍人形/逼近（A3验证）',
    description: '夜间远程警报等待窗已满且雷达仍逼近，期望触发 G2 → A3=是。',
    group: '远程警报等待窗 / A3 校验',
  },
  {
    id: 'B3',
    name: 'A2后等待窗未到（A3不应触发）',
    description: '夜间远程警报等待窗尚未到期，期望 G2 不满足 → A3=否。',
    group: '远程警报等待窗 / A3 校验',
  },
  {
    id: 'B4',
    name: 'A2后等待窗到但已恢复（A3不应触发）',
    description: '夜间远程警报等待窗已满但异常消失，期望 A3=否。',
    group: '远程警报等待窗 / A3 校验',
  },
  {
    id: 'B5',
    name: '白天-A2后等待窗到仍异常（不应A3）',
    description: '白天远程警报等待窗已满且核心仍异常，期望 G2 不命中 → A3=否。',
    group: '远程警报等待窗 / A3 校验',
  },
  {
    id: 'C1',
    name: '白天-核心人形（远程警报可触发）',
    description: '白天 10:00 核心摄像头见人，期望 60 分 / P2 → A2，A3=否。',
    group: '昼夜抑制与链路验证',
  },
  {
    id: 'C2',
    name: '白天-F1×2+F2持续',
    description: '白天 15:30 多源高分仍仅取证，期望 48 分 / P2，A2/A3 均为否。',
    group: '昼夜抑制与链路验证',
  },
  {
    id: 'C3',
    name: '夜间-F1→F2短暂（P不足）',
    description: '夜间 01:40 链路成立但得分 36 分 / P3，A2 不触发。',
    group: '昼夜抑制与链路验证',
  },
  {
    id: 'C4',
    name: '夜间-F1→F2持续（链路）',
    description: '夜间 02:50 链路+持续达标，期望 54 分 / P2 → A2。',
    group: '昼夜抑制与链路验证',
  },
  {
    id: 'C5',
    name: '白名单F1',
    description: '白名单 IMSI 单独出现应被抑制，期望 0 分 / P4，仅 A1。',
    group: '昼夜抑制与链路验证',
  },
  {
    id: 'C6',
    name: '雷达非人形',
    description: '注入雷达噪声/非人形目标应被忽略，期望 0 分 / P4，仅 A1。',
    group: '昼夜抑制与链路验证',
  },
  {
    id: 'C7',
    name: '白天-白名单在场+核心人形（远程警报）',
    description: '白天 11:20 核心摄像头见人且白名单在场，期望 60 分 / P2 → A2。',
    group: '昼夜抑制与链路验证',
  }
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
    description: '严重怀疑的入侵威胁（持续停留或远程警报无效）。',
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
    label: '远程警报',
    color: 'warning',
    description: '已执行远程喊话，等待 5 分钟远程警报窗口反馈。',
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
  if (!state) return '总分'
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

function formatIsoDuration(value: any): string {
  if (typeof value !== 'string' || !value.startsWith('P')) return ''
  const upper = value.toUpperCase()
  const timeIndex = upper.indexOf('T')
  const dateSection = timeIndex === -1 ? upper.slice(1) : upper.slice(1, timeIndex)
  const timeSection = timeIndex === -1 ? '' : upper.slice(timeIndex + 1)
  const parts: string[] = []

  const dateMap: Record<string, string> = {
    Y: '年',
    M: '个月',
    W: '周',
    D: '天',
  }
  const timeMap: Record<string, string> = {
    H: '小时',
    M: '分钟',
    S: '秒',
  }

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

function formatScoreValue(value: number | null | undefined): string {
  if (value == null || Number.isNaN(Number(value))) {
    return '0'
  }
  const fixed = Number(value).toFixed(1)
  return fixed.endsWith('.0') ? fixed.slice(0, -2) : fixed
}

function formatScoreLabel(value: number | null | undefined): string {
  if (value == null || Number.isNaN(Number(value))) {
    return '—'
  }
  return `${formatScoreValue(value)} 分`
}

function priorityClassName(classification: Classification): string {
  const key = classification ? classification.toLowerCase() : 'info'
  return `priority-pill priority-${key}`
}

function formatMultiplier(value: number): string {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric === 0) {
    return '×1.0'
  }
  const formatted = numeric % 1 === 0 ? numeric.toFixed(1) : numeric.toFixed(2)
  return `×${formatted.replace(/0+$/g, '').replace(/\.$/, '')}`
}

type ActionSummary = {
  id: string
  label: string
  status: 'executed' | 'pending' | 'idle'
  detail: string
}

type RuleContribution = { id: string; text: string }
type RuleBreakdown = {
  id: string
  text: string
  score: string | null
  contributions: RuleContribution[]
}

type ScoreSummaryView = {
  base: number
  multiSourceMultiplier: number
  afterMulti: number
  timeMultiplier: number
  total: number
}

function extractFRuleBreakdown(item: RiskAssessment): RuleBreakdown[] {
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

function extractScoreSummary(item: RiskAssessment): ScoreSummaryView | null {
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
  return {
    base,
    multiSourceMultiplier,
    afterMulti,
    timeMultiplier,
    total,
  }
}

function extractActionSummaries(item: RiskAssessment): ActionSummary[] {
  const details: any = item.details
  const actions = Array.isArray(details?.actions) ? details.actions : []
  return actions.map((action: any, index: number) => {
    const id = typeof action?.id === 'string' && action.id ? action.id : `action-${index}`
    const label = typeof action?.definition?.name === 'string' && action.definition.name ? action.definition.name : id
    const triggered = Boolean(action?.triggered)
    const recommended = Boolean(action?.recommended)
    const status: ActionSummary['status'] = triggered ? 'executed' : recommended ? 'pending' : 'idle'
    const rationale = typeof action?.rationale === 'string' && action.rationale ? action.rationale : ''
    const decidedAt = action?.decidedAt ? formatTime(action.decidedAt) : ''
    const parts = [
      triggered ? '已执行' : recommended ? '待执行' : '未触发',
      rationale,
      decidedAt ? `时间：${decidedAt}` : '',
    ].filter(Boolean)
    return {
      id,
      label,
      status,
      detail: parts.join(' ｜ '),
    }
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
      <h2 class="live-title">
        实时风险态势
        <span
          v-if="latestAssessment"
          class="live-score"
          :class="priorityClassName(latestAssessment.classification)"
        >
          {{ formatPriority(latestAssessment) }} · {{ formatScoreLabel(latestAssessment.score) }}
        </span>
      </h2>
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
                        <span
                          class="risk-item-title"
                          :class="priorityClassName(item.classification)"
                        >
                          {{ formatPriority(item) }} · {{ formatScoreLabel(item.score) }}
                        </span>
                        <span
                          v-if="item.state"
                          class="risk-item-state"
                          :class="`state-${(item.state || 'unknown').toLowerCase()}`"
                        >
                          {{ formatStateLabel(item.state) }}
                        </span>
                      </div>
                      <div v-if="item.summary || extractFRuleBreakdown(item).length" class="risk-score-card">
                        <div class="score-card-header">评分详情</div>
                        <div v-if="item.summary" class="score-card-summary">{{ item.summary }}</div>
                        <div v-if="extractScoreSummary(item)" class="score-summary-grid">
                          <div class="score-summary-row">
                            <span>基础得分</span>
                            <span>{{ formatScoreValue(extractScoreSummary(item)?.base ?? 0) }}</span>
                          </div>
                          <div class="score-summary-row">
                            <span>协同系数</span>
                            <span>{{ formatMultiplier(extractScoreSummary(item)?.multiSourceMultiplier ?? 1) }}</span>
                          </div>
                          <div class="score-summary-row">
                            <span>协同后</span>
                            <span>{{ formatScoreValue(extractScoreSummary(item)?.afterMulti ?? 0) }}</span>
                          </div>
                          <div class="score-summary-row">
                            <span>昼夜系数</span>
                            <span>{{ formatMultiplier(extractScoreSummary(item)?.timeMultiplier ?? 1) }}</span>
                          </div>
                          <div class="score-summary-row">
                            <span>综合得分</span>
                            <span>{{ formatScoreValue(extractScoreSummary(item)?.total ?? 0) }}</span>
                          </div>
                        </div>
                        <div v-if="extractFRuleBreakdown(item).length" class="score-card-body">
                          <div
                            v-for="hit in extractFRuleBreakdown(item)"
                            :key="hit.id"
                            class="score-card-block"
                          >
                            <div class="score-block-title">
                              <span class="score-row-label">{{ hit.id }}</span>
                              <span class="score-row-text">{{ hit.text }}</span>
                            </div>
                            <div v-if="hit.score" class="score-block-score">{{ hit.score }}</div>
                            <div v-if="hit.contributions && hit.contributions.length" class="score-block-contrib">
                              <div
                                v-for="contrib in hit.contributions"
                                :key="contrib.id"
                                class="score-contrib-row"
                              >
                                {{ contrib.text }}
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                      <div v-if="extractActionSummaries(item).length" class="risk-item-actions">
                        <div
                          v-for="action in extractActionSummaries(item)"
                          :key="action.id"
                          class="risk-action"
                          :class="`risk-action-${action.status}`"
                        >
                          <span class="action-badge" :class="`action-badge-${action.id.toLowerCase()}`">
                            {{ action.id }}
                          </span>
                          <div class="action-body">
                            <span class="action-label">{{ action.label }}</span>
                            <span class="action-detail">{{ action.detail }}</span>
                          </div>
                        </div>
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

    <section class="model-doc">
      <h2>风控模型规则（v0.51｜三圈层统一版）</h2>
      <p>
        v0.51 版在现有三圈层与分值体系上统一了关键常量：<strong>摄像头 1–40 m</strong>、<strong>雷达 10–150 m（&lt;10 m 物理盲区）</strong>、
        <strong>IMSI ≈500 m</strong>，并将 A2 全面改名为 <strong>“远程警报”</strong>。新增白天例外闸门 G1‑D‑X，用于解决白天高分仍停留 A1 的矛盾。
      </p>
      <p>
        核心原则：<strong>白天仅核心越界或严苛例外组合可触发远程警报</strong>；<strong>夜间核心入侵绝不豁免</strong>，远程警报等待窗（T=300 s）
        到期仍异常必须升级 A3；<strong>白名单 IMSI 仅用于 F1 降噪</strong>。
      </p>

      <h3>0）几何与时序常量（统一参数）</h3>
      <table class="model-table">
        <thead>
          <tr>
            <th>参数项</th>
            <th class="numeric">固定数值</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>核心区摄像头有效探测</td>
            <td class="numeric">1–40 m</td>
            <td>跨越警戒线且距摄像头 1–40 m 识别人形即记 F3</td>
          </tr>
          <tr>
            <td>雷达有效探测</td>
            <td class="numeric">10–150 m</td>
            <td>&lt;10 m 不可见；10–20 m 为近域加权区</td>
          </tr>
          <tr>
            <td>IMSI 外圈覆盖</td>
            <td class="numeric">≈500 m</td>
            <td>白名单仅用于外圈降噪，不影响其他规则</td>
          </tr>
          <tr>
            <td>夜间时段</td>
            <td class="numeric">18:00–06:00</td>
            <td>仅夜间允许 A3（人员出动）</td>
          </tr>
          <tr>
            <td>融合归并窗 mergeWindow</td>
            <td class="numeric">300 s</td>
            <td>5 min 内的多源触发归并为同一事件</td>
          </tr>
          <tr>
            <td>远程警报等待窗 T</td>
            <td class="numeric">300 s</td>
            <td>A2 执行后等待 5 min 复评结果</td>
          </tr>
          <tr>
            <td>F2 持续阈值 τ</td>
            <td class="numeric">10 s</td>
            <td>雷达人形持续存在的判定门槛</td>
          </tr>
          <tr>
            <td>F1→F2 链路窗 Δt</td>
            <td class="numeric">240 s</td>
            <td>同扇区 F1 在先，Δt 内出现 F2 视为同一逼近链路</td>
          </tr>
          <tr>
            <td>IMSI 去重窗</td>
            <td class="numeric">300 s</td>
            <td>同一 IMSI 5 min 内不重复计“首次出现”</td>
          </tr>
          <tr>
            <td>IMSI 重现窗</td>
            <td class="numeric">1800 s</td>
            <td>30 min 内再次出现计“重复出现”</td>
          </tr>
        </tbody>
      </table>
      <p class="doc-note">雷达与摄像头在 10–40 m 区间可协同；<strong>&lt;10 m 由摄像头单独覆盖</strong>。</p>

      <h3>1）F 规则与逐项加分（仅三类设备）</h3>
      <p>先累加 F1/F2/F3 子项得分 → 若满足“多源协同”乘以 ×1.2 → 再乘昼夜因子（夜 ×1.5 / 昼 ×1.0）。</p>

      <h4>F1 外圈 / IMSI（≈500 m；白名单仅影响 F1）</h4>
      <table class="model-table">
        <thead>
          <tr>
            <th>代号</th>
            <th>判定条件（mergeWindow 内）</th>
            <th class="numeric">加分</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>outer_unknown_cnt</td>
            <td>非白名单 IMSI 首次出现去重计数</td>
            <td class="numeric">+10 × min(计数, 2)</td>
          </tr>
          <tr>
            <td>outer_repeat</td>
            <td>同一非白名单 IMSI 30 min 内再次出现</td>
            <td class="numeric">+8</td>
          </tr>
          <tr>
            <td>whitelist_suppress</td>
            <td>IMSI 在白名单 → 本窗不计分、亦不计协同</td>
            <td class="numeric">0</td>
          </tr>
        </tbody>
      </table>

      <h4>F2 中圈 / 雷达（10–150 m；10–20 m 近域）</h4>
      <table class="model-table">
        <thead>
          <tr>
            <th>代号</th>
            <th>判定条件</th>
            <th class="numeric">加分</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>mid_short</td>
            <td>人形/类人形轨迹，持续 &lt;10 s</td>
            <td class="numeric">+10</td>
          </tr>
          <tr>
            <td>mid_persist</td>
            <td>人形/类人形轨迹，持续 ≥10 s</td>
            <td class="numeric">+20</td>
          </tr>
          <tr>
            <td>approach_core</td>
            <td>有效轨迹向核心单调逼近（半径下降 ≥1.5 m）</td>
            <td class="numeric">+8</td>
          </tr>
          <tr>
            <td>near_core_10_20m</td>
            <td>轨迹进入 10–20 m 近域带</td>
            <td class="numeric">+20</td>
          </tr>
        </tbody>
      </table>
      <p class="doc-note">F2 仅统计 10–150 m 有效回波，含 10.0 与 20.0 边界；<strong>&lt;10 m 仅靠摄像头佐证</strong>，近域加权与持续/逼近可叠加。</p>

      <h4>F3 核心区 / 摄像头（1–40 m 有效）</h4>
      <table class="model-table">
        <thead>
          <tr>
            <th>代号</th>
            <th>判定条件</th>
            <th class="numeric">加分</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>core_human</td>
            <td>核心警戒线被 1–40 m 内摄像头识别人形跨越</td>
            <td class="numeric">+60</td>
          </tr>
        </tbody>
      </table>

      <h3>2）协同与时段乘子（先协同，后昼夜）</h3>
      <table class="model-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>触发条件</th>
            <th class="numeric">乘子</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>多源协同</td>
            <td>mergeWindow 内 ≥2 类 F 成立（白名单 IMSI 不计）</td>
            <td class="numeric">×1.2</td>
          </tr>
          <tr>
            <td>夜间乘子</td>
            <td>18:00–06:00</td>
            <td class="numeric">×1.5</td>
          </tr>
          <tr>
            <td>白天乘子</td>
            <td>06:00–18:00</td>
            <td class="numeric">×1.0</td>
          </tr>
        </tbody>
      </table>

      <h3>3）P 等级阈值（P1 最高）</h3>
      <table class="model-table">
        <thead>
          <tr>
            <th>等级</th>
            <th class="numeric">综合得分</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>P1</td>
            <td class="numeric">≥70</td>
            <td>核心越界或雷达近域持续逼近，必须远程警报</td>
          </tr>
          <tr>
            <td>P2</td>
            <td class="numeric">40–69</td>
            <td>雷达近域/链路等高风险线索，执行远程警报</td>
          </tr>
          <tr>
            <td>P3</td>
            <td class="numeric">15–39</td>
            <td>外围弱线索或单一感知，持续留痕</td>
          </tr>
          <tr>
            <td>P4</td>
            <td class="numeric">&lt;15</td>
            <td>仅记录，无需处置</td>
          </tr>
        </tbody>
      </table>

      <h3>4）A 类动作（内容 / 时机 / 去重）</h3>
      <table class="model-table">
        <thead>
          <tr>
            <th>动作</th>
            <th>内容</th>
            <th>触发时机</th>
            <th>去重</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>A1 – 取证</td>
            <td>自动标注事件并留存录像</td>
            <td>任一 F 规则成立即执行</td>
            <td>每事件 1 次</td>
          </tr>
          <tr>
            <td>A2 – 远程警报</td>
            <td>灯光 / 语音 / 警笛一次性触发</td>
            <td>命中 G1‑N / G1‑D / G1‑D‑X 任一闸门</td>
            <td>每事件 1 次，启动等待窗 T=300 s</td>
          </tr>
          <tr>
            <td>A3 – 人员出动</td>
            <td>通知安保到场</td>
            <td>夜间 G2：远程警报等待窗到期仍异常</td>
            <td>每事件 1 次</td>
          </tr>
        </tbody>
      </table>

      <h3>5）G 闸门（昼/夜分流，含白天例外）</h3>
      <ul class="doc-list">
        <li>
          <strong>G1 夜间（G1‑N）：</strong>核心摄像头见人，或 P≥P2 且满足 mid_persist / near_core_10_20m / link_f1_f2 任一 → 执行 A2。
        </li>
        <li>
          <strong>G1 白天（G1‑D）：</strong>仅核心越界（F3）可触发远程警报，其他线索默认仅 A1 取证。
        </li>
        <li>
          <strong>G1 白天例外（G1‑D‑X）：</strong>白天无 F3，且同时满足① P≥70；② 雷达 mid_persist & near_core_10_20m & approach_core；③ outer_repeat / link_f1_f2 / outer_unknown_cnt≥2 任一 → 执行 A2。
        </li>
        <li>
          <strong>G2：</strong>夜间且远程警报等待窗到期仍异常（核心见人或雷达持续逼近）→ 执行 A3。
        </li>
        <li>
          <strong>G3：</strong>白天未命中 G1‑N/G1‑D/G1‑D‑X，仅执行 A1 取证。
        </li>
      </ul>

      <h3>6）事件生命周期与去重</h3>
      <ul class="doc-list">
        <li><strong>事件归并键：</strong>{站点、扇区/相机 ID、时间桶（mergeWindow=300 s）}。</li>
        <li><strong>A2/A3 去重：</strong>每事件 A2 最多 1 次（T=300 s 内不重复），A3 仅夜间且最多 1 次。</li>
        <li><strong>结案与冷却：</strong>T 内或 mergeWindow 后无续发，且核心/雷达均无异常 → 自动结案。</li>
        <li><strong>白天 A2 限骚扰（推荐执行层参数）：</strong>A2_day_burst_sec=8，A2_day_cooldown_sec=600。</li>
      </ul>

      <h3>7）边界一致性校验（验收清单）</h3>
      <ul class="doc-list">
        <li><strong>雷达 &lt;10 m：</strong>F2=0；10.0/20.0/150.0 视为有效边界值；&gt;150 m 无效。</li>
        <li><strong>摄像头 1.0–40.0 m：</strong>越界有效；&gt;40 m 视为无效。</li>
        <li><strong>白天无 F3：</strong>仅命中 G1‑D‑X 的严苛组合可 A2；否则无论得分多高仅 A1。</li>
        <li><strong>A3 永远仅夜间：</strong>白天即使等待窗到期仍异常也不派员。</li>
      </ul>

      <h3>8）数值示例（3 条）</h3>
      <ol class="doc-list">
        <li>
          <strong>白天·例外触发 A2（无 F3）：</strong>F2=mid_persist 20 + near_core_10_20m 20 + approach_core 8 = 48；F1：outer_unknown_cnt=2 ⇒ 20；协同 ×1.2 ⇒ 81.6 → P1 → G1‑D‑X 命中 → A2。
        </li>
        <li>
          <strong>白天·仅 F2（近域持续）但证据不足：</strong>F2=40 → P2；无 F1 佐证、无 F3 → 仅 A1，不触发 G1‑D‑X。
        </li>
        <li>
          <strong>夜间·雷达 12 m 持续：</strong>F2=40 → 夜乘子 ⇒ 60（P2） → G1‑N → A2；等待窗到期仍异常 → G2 → A3。
        </li>
      </ol>

      <h3>9）白名单定位（不变）</h3>
      <ul class="doc-list">
        <li>白名单 IMSI 仅影响 F1：记 0 分且不参与协同；F2/F3 与所有 G 闸门不受影响。</li>
        <li>夜间核心入侵即便白名单在场，仍按闸门执行 A2/A3。</li>
      </ul>
    </section>


  </div>
</template>


<style scoped>
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

.scenario-card :deep(.ant-card-head-title) {
  white-space: normal;
  word-break: break-word;
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

.scenario-block {
  margin-top: 24px;
}

.scenario-block:first-of-type {
  margin-top: 16px;
}

.scenario-block h4 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.85);
}

.scenario-list {
  margin-top: 12px;
  padding-left: 20px;
}

.scenario-list > li {
  margin-bottom: 12px;
}

.scenario-line {
  margin-top: 4px;
  color: rgba(255, 255, 255, 0.75);
}

.scenario-label {
  font-weight: 600;
  color: rgba(255, 255, 255, 0.85);
  margin-right: 4px;
}

.scenario-table th,
.scenario-table td {
  text-align: left;
}

.scenario-table td strong {
  color: #ffd666;
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
  gap: 12px;
}

.risk-item-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.risk-item-title {
  font-size: 16px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.priority-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 12px;
  border-radius: 999px;
  font-size: 14px;
}

.priority-p1 {
  background: rgba(255, 77, 79, 0.18);
  border: 1px solid rgba(255, 77, 79, 0.6);
  color: #ff7875;
}

.priority-p2 {
  background: rgba(250, 173, 20, 0.16);
  border: 1px solid rgba(250, 173, 20, 0.5);
  color: #ffc069;
}

.priority-p3 {
  background: rgba(19, 194, 194, 0.15);
  border: 1px solid rgba(19, 194, 194, 0.45);
  color: #87e8de;
}

.priority-p4 {
  background: rgba(82, 196, 26, 0.15);
  border: 1px solid rgba(82, 196, 26, 0.45);
  color: #95de64;
}

.priority-info {
  background: rgba(120, 120, 120, 0.15);
  border: 1px solid rgba(140, 140, 140, 0.45);
  color: #d9d9d9;
}

.risk-item-state {
  font-weight: 600;
  color: rgba(255, 255, 255, 0.85);
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

.risk-score-card {
  display: grid;
  gap: 8px;
  padding: 12px;
  background: rgba(12, 24, 36, 0.45);
  border-radius: 10px;
  border: 1px solid rgba(64, 169, 255, 0.28);
}

.score-card-header {
  font-weight: 700;
  color: rgba(255, 255, 255, 0.95);
}

.score-card-summary {
  color: rgba(255, 255, 255, 0.85);
}

.score-card-body {
  display: grid;
  gap: 6px;
}

.score-summary-grid {
  display: grid;
  gap: 4px;
  padding: 6px 8px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 6px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.8);
}

.score-summary-row {
  display: flex;
  justify-content: space-between;
}

.score-card-block {
  display: grid;
  gap: 6px;
  padding: 8px 10px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 8px;
}

.score-block-title {
  display: grid;
  grid-template-columns: 56px 1fr;
  gap: 8px;
  align-items: baseline;
}

.score-row-label {
  font-weight: 600;
  color: rgba(64, 169, 255, 0.9);
}

.score-row-text {
  color: rgba(255, 255, 255, 0.8);
  word-break: break-word;
}

.score-block-score {
  font-size: 13px;
  color: rgba(255, 214, 102, 0.95);
}

.score-block-contrib {
  display: grid;
  gap: 4px;
  padding-left: 12px;
  border-left: 2px dotted rgba(64, 169, 255, 0.3);
}

.score-contrib-row {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
}

.risk-item-actions {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  background: rgba(0, 0, 0, 0.25);
  border-radius: 8px;
}

.risk-action {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  line-height: 1.4;
}

.action-badge {
  min-width: 32px;
  text-align: center;
  font-weight: 700;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 13px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.18);
}

.action-badge-a1 {
  color: #69c0ff;
  border-color: rgba(105, 192, 255, 0.6);
}

.action-badge-a2 {
  color: #faad14;
  border-color: rgba(250, 173, 20, 0.6);
}

.action-badge-a3 {
  color: #ff4d4f;
  border-color: rgba(255, 77, 79, 0.6);
}

.action-body {
  display: grid;
  gap: 2px;
}

.action-label {
  font-weight: 600;
  color: rgba(255, 255, 255, 0.9);
}

.action-detail {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.7);
}

.risk-action-executed .action-label {
  color: #ffd666;
}

.risk-action-pending .action-label {
  color: #69c0ff;
}

.risk-action-idle .action-label {
  color: rgba(255, 255, 255, 0.65);
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

.model-doc {
  margin-top: 32px;
  display: grid;
  gap: 16px;
}

.model-doc h3,
.model-doc h4 {
  margin-bottom: 8px;
  color: #fff;
}

.model-doc hr {
  border: none;
  border-top: 1px solid rgba(255, 255, 255, 0.2);
}

.model-table {
  width: 100%;
  border-collapse: collapse;
  background: rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.12);
}

.model-table th,
.model-table td {
  padding: 8px 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  text-align: left;
  color: rgba(255, 255, 255, 0.85);
}

.model-table th {
  background: rgba(255, 255, 255, 0.05);
  font-weight: 600;
}

.model-table .numeric {
  text-align: center;
  white-space: nowrap;
}

.doc-note {
  margin: 0;
  padding: 8px 12px;
  background: rgba(255, 255, 255, 0.06);
  border-left: 3px solid #69c0ff;
  color: rgba(255, 255, 255, 0.8);
}

.doc-list {
  margin: 0;
  padding-left: 18px;
  display: grid;
  gap: 6px;
  color: rgba(255, 255, 255, 0.85);
}

.live-title {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.live-score {
  font-size: 18px;
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

.risk-item-breakdown .rule-desc {
  display: block;
  word-break: break-all;
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
