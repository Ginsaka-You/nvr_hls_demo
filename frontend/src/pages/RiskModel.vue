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

    <section class="model-doc">
      <h2>风控模型规则（2024Q3 修订）</h2>
      <p>
        下面是逐条自查并修复后的最终版规则表。仅保留三类设备（IMSI 外圈≈500 m、雷达中圈10–150 m、摄像头核心区30–50 m），仅夜间允许 A3（人员出动），
        A2 必须先于 A3 并以挑战窗口 T=5 min 为闸门，不做设备调参。对上版存在的模糊或冲突点已修复并在文末列出更正要点。
      </p>

      <hr />

      <h3>0）固定时间段与窗口参数（统一、可直接落地）</h3>
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
            <td>夜间时段</td>
            <td class="numeric">18:00–06:00</td>
            <td>仅夜间允许 A3（人员出动）；白天不执行 A2/A3（只取证）</td>
          </tr>
          <tr>
            <td>融合归并窗 mergeWindow</td>
            <td class="numeric">300 s</td>
            <td>同一事件 5 min 内的多传感触发归并融合</td>
          </tr>
          <tr>
            <td>挑战窗口 T</td>
            <td class="numeric">300 s</td>
            <td>A2（灯光+喊话）后等待 T，与 IMSI 再识别周期对齐</td>
          </tr>
          <tr>
            <td>F2 持续阈值 τ</td>
            <td class="numeric">10 s</td>
            <td>雷达人形“持续”判定阈值（由 40 s 下调）</td>
          </tr>
          <tr>
            <td>F1→F2 链路窗 Δt</td>
            <td class="numeric">240 s</td>
            <td>同扇区 F1 在先，Δt 内出现 F2 视为同一逼近链路</td>
          </tr>
          <tr>
            <td>IMSI 去重窗</td>
            <td class="numeric">300 s</td>
            <td>同一 IMSI 5 min 内不计“首次出现”重复</td>
          </tr>
          <tr>
            <td>IMSI 重现窗</td>
            <td class="numeric">1800 s</td>
            <td>同一未知 IMSI 30 min 内再次出现计“重复出现”</td>
          </tr>
          <tr>
            <td>F2 近域接近阈值（距核心警戒线）</td>
            <td class="numeric">≤ 10 m</td>
            <td>由 150 m 收紧，使用雷达量测到核心警戒线外侧的距离</td>
          </tr>
        </tbody>
      </table>
      <p class="doc-note">
        注：圈层可物理重叠（例如核心边缘同时被雷达与摄像覆盖）；融合通过时间窗/扇区归并处理，不再宣称“互不重叠”。
      </p>

      <h3>1）F 规则：前端触发与逐项加分</h3>
      <p>
        先累加 F1/F2/F3 的子项分，再按 §2 的协同 ×1.2 与昼/夜乘子（夜 ×1.5 / 昼 ×1.0）依次应用得到综合得分。
      </p>

      <h4>F1 外圈 / IMSI（≈500 m，覆盖村庄；白名单仅影响 F1）</h4>
      <table class="model-table">
        <thead>
          <tr>
            <th>代号</th>
            <th>判定条件（均在 mergeWindow 内统计）</th>
            <th class="numeric">加分</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>outer_unknown_cnt</td>
            <td>同一扇区内出现非白名单 IMSI 的去重计数</td>
            <td class="numeric">+10 × min(计数, 2)（最多 +20）</td>
          </tr>
          <tr>
            <td>outer_repeat</td>
            <td>同一非白名单 IMSI 在 IMSI 重现窗（30 min）内再次出现</td>
            <td class="numeric">+8</td>
          </tr>
          <tr>
            <td>whitelist_suppress</td>
            <td>IMSI 在白名单</td>
            <td class="numeric">0 分（该 IMSI 在本窗内不计分）</td>
          </tr>
        </tbody>
      </table>
      <p class="doc-note">
        设计意图：外圈覆盖村庄，F1 单独是弱线索；夜间通过乘子放大，或与 F2/F3 形成链路后意义增大。
      </p>

      <h4>F2 中圈 / 雷达（10–150 m；阈值收紧：距离 ≤ 10 m，持续 ≥ 10 s）</h4>
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
            <td>目标为人形/类人形，但持续 &lt; τ=10 s</td>
            <td class="numeric">+10</td>
          </tr>
          <tr>
            <td>mid_persist</td>
            <td>人形/类人形 且 持续 ≥ τ=10 s</td>
            <td class="numeric">+20</td>
          </tr>
          <tr>
            <td>approach_core</td>
            <td>目标轨迹向核心逼近（雷达量测半径单调递减/速度落差接近）</td>
            <td class="numeric">+8（可叠加）</td>
          </tr>
          <tr>
            <td>near_core_10m</td>
            <td>目标进入距核心警戒线 ≤ 10 m 的近域接近</td>
            <td class="numeric">+20（可与上叠加）</td>
          </tr>
        </tbody>
      </table>
      <p class="doc-note">
        约束：务农/动物由人形分类与速度/尺寸门限抑制。变更：τ 由 40 s → 10 s，“近域接近”由 150 m 收紧为 ≤ 10 m 并单列加分。
      </p>

      <h4>F3 核心区 / 摄像头（30–50 m；核心区=警戒线内）</h4>
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
            <td>识别人形跨越核心区警戒线（昼/夜均计分）</td>
            <td class="numeric">+60</td>
          </tr>
        </tbody>
      </table>
      <p class="doc-note">
        白天只取证；夜间命中闸门先 A2，挑战窗口失败再 A3。
      </p>

      <h3>2）协同与昼/夜乘子（应用顺序固定）</h3>
      <table class="model-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>触发条件</th>
            <th class="numeric">系数/乘子</th>
            <th>应用顺序</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>多源协同</td>
            <td>mergeWindow 内 ≥2 类设备成立（任意 F1/F2/F3 组合）</td>
            <td class="numeric">× 1.2</td>
            <td>先应用</td>
          </tr>
          <tr>
            <td>夜间乘子</td>
            <td>18:00–06:00</td>
            <td class="numeric">× 1.5</td>
            <td>后应用</td>
          </tr>
          <tr>
            <td>白天乘子</td>
            <td>06:00–18:00</td>
            <td class="numeric">× 1.0</td>
            <td>后应用</td>
          </tr>
        </tbody>
      </table>

      <h3>3）P 等级阈值（P1 最高；仅做强度表述与闸门前置条件）</h3>
      <table class="model-table">
        <thead>
          <tr>
            <th>P 等级</th>
            <th>综合得分区间（含协同/昼夜加权后）</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>P1</td>
            <td>≥ 70</td>
          </tr>
          <tr>
            <td>P2</td>
            <td>40–69</td>
          </tr>
          <tr>
            <td>P3</td>
            <td>15–39</td>
          </tr>
          <tr>
            <td>P4</td>
            <td>&lt; 15</td>
          </tr>
        </tbody>
      </table>
      <p class="doc-note">
        P 仅用于强度表达与入闸门判断，绝不直接触发 A3（修复了“以 P 直接派警”的潜在歧义）。
      </p>

      <h3>4）A 类动作（动作定义、触发时机、去重）</h3>
      <table class="model-table">
        <thead>
          <tr>
            <th>动作</th>
            <th>内容</th>
            <th>触发时机</th>
            <th>频率/去重</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>A1 – 取证记录</td>
            <td>系统标注与录像留存（纯逻辑，不改设备）</td>
            <td>任一 F 事件成立即进入事件周期时</td>
            <td>每事件自动进行</td>
          </tr>
          <tr>
            <td>A2 – 远程挑战</td>
            <td>灯光 + 语音喊话（一次性）</td>
            <td>命中 G1 闸门时立即执行</td>
            <td>每事件仅 1 次，并启动 T=300 s</td>
          </tr>
          <tr>
            <td>A3 – 人员出动</td>
            <td>通知安保到场（非公安）</td>
            <td>命中 G2 闸门（A2 后 T 到且仍异常），且仅夜间</td>
            <td>每事件仅 1 次</td>
          </tr>
        </tbody>
      </table>
      <p class="doc-note">
        执行顺序固定：A1 →（必要时）A2 →（T 失败且夜间）A3；白天禁止 A2/A3。
      </p>

      <h3>5）G 闸门（与 A 动作绑定；修复了 IMSI 单独升级的歧义）</h3>
      <p class="doc-note">
        记：link_f1_f2 = 同扇区内 F1 未知 IMSI 在先，Δt=240 s 内出现 F2。
      </p>
      <table class="model-table">
        <thead>
          <tr>
            <th>闸门</th>
            <th>命中条件</th>
            <th>时段</th>
            <th>动作</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>G1（入 A2）</td>
            <td>夜间且满足任一：① core_human；② P ≥ P2 且（mid_persist 或 near_core_10m 或 link_f1_f2）</td>
            <td>夜</td>
            <td>执行 A2 一次并启动 T=300 s</td>
          </tr>
          <tr>
            <td>G2（入 A3）</td>
            <td>夜间且 T 到且仍满足任一：① 核心仍见人形；② 雷达仍人形/仍向核心逼近</td>
            <td>夜</td>
            <td>执行 A3 一次（通知安保到场）</td>
          </tr>
          <tr>
            <td>G3（白天仅取证）</td>
            <td>任意 P 等级</td>
            <td>昼</td>
            <td>只执行 A1（不做 A2/A3）</td>
          </tr>
        </tbody>
      </table>
      <p class="doc-note">
        修复点：移除了“仅凭 IMSI 仍在近域就可 A3”的表述——IMSI 只能作为弱佐证，不作为 G2 的单独充分条件。
      </p>

      <h3>6）典型情景 → 计算示例（便于验收/联调）</h3>
      <p>公式：综合得分 = (ΣF 子项加分 × 协同 1.2（若触发）) × 昼/夜乘子</p>
      <ol class="doc-list">
        <li>
          <strong>夜间核心摄像头见人（仅 F3）</strong><br />
          F3：core_human = +60 → 协同无 → 夜 × 1.5 → 总分 90 = P1。命中 G1 → A2；T=300 s 后若核心仍见人/雷达仍见人形 → G2 → A3。
        </li>
        <li>
          <strong>夜间雷达人形持续 12 s，且距核心 ≤ 10 m（仅 F2）</strong><br />
          F2：mid_persist +20、near_core_10m +20（若还有 approach_core +8）→ 小计 40（或 48）；协同无 → 夜 × 1.5 → 60（或 72）→ P2（或触及 P1）。
          满足 G1 → A2；T 失败 → A3。
        </li>
        <li>
          <strong>夜间 F1 未知 IMSI，3 min 内同扇区出现 F2 短暂人形（链路成立）</strong><br />
          F1：+10；F2：mid_short +10 → 小计 20 → 协同 × 1.2 = 24 → 夜 × 1.5 = 36 = P3 高位。
          若叠加 approach_core +8 → 小计 28 → ×1.2 = 33.6 → 夜 × 1.5 = 50.4 = P2 → 命中 G1 → A2。
        </li>
        <li>
          <strong>白天核心见人（仅 F3）</strong><br />
          F3：+60 → 昼 × 1.0 → 60 = P2。G3：白天仅 A1（取证），不 A2/A3。
        </li>
        <li>
          <strong>夜间多未知 IMSI（2 个，F1=+20）+ F2 短暂人形 + 逼近</strong><br />
          F1：+20；F2：mid_short +10、approach_core +8 → 小计 38 → 协同 × 1.2 = 45.6 → 夜 × 1.5 = 68.4 ≈ P2 高位。命中 G1 → A2；T 失败 → A3。
        </li>
      </ol>

      <h3>7）去重 / 归并 / 冷却（最小生命周期语义，仅逻辑）</h3>
      <ul class="doc-list">
        <li>归并键：{站点、扇区/相机 ID、时间桶（mergeWindow=300 s）}；同窗内 F1/F2/F3 归并为一事件。</li>
        <li>A2/A3 去重：每事件 A2 仅 1 次；若 A2 已执行，T 内不再重复；A3 亦仅 1 次。</li>
        <li>结案：若在 T 内或 mergeWindow 后无任何续发，且核心/雷达均无异常，则自动结案并进入冷却。</li>
      </ul>

      <h3>自查与修复要点（本版相对上版的冲突消解）</h3>
      <ol class="doc-list">
        <li>去掉“圈层互不重叠”的旧表述：允许多传感器在核心边缘同时感知，通过归并+协同加权处理。</li>
        <li>IMSI 不再作为 A3 的独立充分条件：避免“手机在附近但人已不在”的误派警。</li>
        <li>F2 双阈值收紧：持续阈 τ=10 s、近域接近 ≤ 10 m（雷达量测，无需摄像头测距）。</li>
        <li>动作闸门“先 A2 后 A3”严格化：A3 只能由“夜间 + 挑战失败”触发；P 级永不直接派警。</li>
        <li>白天策略统一：白天固定不做 A2/A3，只执行 A1 取证。</li>
        <li>参数对齐：挑战窗口 T=5 min 与 IMSI 再识别周期统一；各时间窗互不矛盾。</li>
        <li>术语澄清：核心区距警戒线 ≤ 10 m 由雷达测距；摄像头只做越界人形识别。</li>
      </ol>
      <p class="doc-note">
        以上版本以数值固定、触发明确、顺序刚性的形式提供：加分 → 协同 → 昼/夜乘子 → P 级 → G 闸门 → A 动作，可直接用于工程侧规则实现与联调验收。
      </p>
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
