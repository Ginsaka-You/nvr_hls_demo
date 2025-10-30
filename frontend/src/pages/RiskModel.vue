<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'

type Classification = 'BLACK' | 'STRONG_ALERT' | 'GRAY' | 'WHITE' | 'LOG_ONLY'
type SubjectType = 'IMSI' | 'CAMERA' | 'RADAR' | string

type RiskAssessment = {
  id: number
  subjectType: SubjectType
  subjectKey: string
  classification: Classification
  score: number | null
  summary: string | null
  windowStart: string | null
  windowEnd: string | null
  updatedAt: string | null
  details: Record<string, unknown> | null
}

const REFRESH_INTERVAL = 15000
const loading = ref(false)
const assessments = ref<RiskAssessment[]>([])
const errorMessage = ref<string | null>(null)
const pollingTimer = ref<number | null>(null)

const classificationOrder: Classification[] = ['BLACK', 'STRONG_ALERT', 'GRAY', 'WHITE']
const classificationMeta: Record<Classification, { label: string; tag: string; empty: string; description: string }> = {
  BLACK: { label: '黑名单', tag: 'error', empty: '暂无黑名单事件', description: '评分 ≥70 或命中黑触发' },
  STRONG_ALERT: { label: '强警戒', tag: 'warning', empty: '暂无强警戒目标', description: '评分 55–69，或夜间桶数 ≥3 / 停留 ≥15min' },
  GRAY: { label: '灰观察', tag: 'processing', empty: '暂无灰名单目标', description: '评分 30–54，或被灰规则标记' },
  WHITE: { label: '白名单', tag: 'success', empty: '暂无自动识别白名单', description: '满足农事白模式等条件' },
  LOG_ONLY: { label: '仅记录', tag: 'default', empty: '暂无', description: '低风险，仅留存日志' },
}

const subjectLabels: Record<string, string> = {
  IMSI: 'IMSI 设备',
  CAMERA: '摄像头',
  RADAR: '雷达目标',
}

const groupedAssessments = computed(() => {
  const base: Record<string, RiskAssessment[]> = {}
  Object.keys(classificationMeta).forEach((key) => {
    base[key] = []
  })
  for (const item of assessments.value) {
    const key = classificationMeta[item.classification] ? item.classification : 'LOG_ONLY'
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

const logAssessments = computed(() => groupedAssessments.value.LOG_ONLY ?? [])
const boardHasData = computed(() =>
  classificationOrder.some((key) => (groupedAssessments.value[key]?.length ?? 0) > 0)
)

function toClassification(value: any): Classification {
  const text = typeof value === 'string' ? value.toUpperCase() : ''
  if (classificationMeta[text as Classification]) {
    return text as Classification
  }
  return 'LOG_ONLY'
}

function normalizeAssessment(raw: any): RiskAssessment {
  const classification = toClassification(raw?.classification)
  return {
    id: Number(raw?.id ?? 0),
    subjectType: typeof raw?.subjectType === 'string' ? raw.subjectType : 'UNKNOWN',
    subjectKey: raw?.subjectKey != null ? String(raw.subjectKey) : '-',
    classification,
    score: raw?.score != null ? Number(raw.score) : null,
    summary: raw?.summary != null ? String(raw.summary) : null,
    windowStart: raw?.windowStart ?? null,
    windowEnd: raw?.windowEnd ?? null,
    updatedAt: raw?.updatedAt ?? raw?.windowEnd ?? null,
    details: raw?.details && typeof raw.details === 'object' ? (raw.details as Record<string, unknown>) : null,
  }
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

function formatScore(score: number | null | undefined): string {
  if (typeof score === 'number' && Number.isFinite(score)) {
    return `${score} 分`
  }
  return '—'
}

function subjectLabel(type: string): string {
  return subjectLabels[type] || type || '未知'
}

function extractTopRule(item: RiskAssessment): string | null {
  const details: any = item.details
  const scoreHits = Array.isArray(details?.scoreHits) ? details.scoreHits : []
  if (scoreHits.length > 0) {
    const first = scoreHits[0] ?? {}
    const desc = typeof first.description === 'string' ? first.description : ''
    const pts = Number(first.score ?? 0)
    if (desc && pts > 0) return `${desc} (+${pts})`
    if (desc) return desc
  }
  return null
}

function extractFlags(item: RiskAssessment): Array<{ label: string; color: string }> {
  const details: any = item.details
  const flags: Array<{ label: string; color: string }> = []
  if (Array.isArray(details?.directBlack) && details.directBlack.length) {
    flags.push({ label: '黑触发', color: 'error' })
  }
  if (Array.isArray(details?.forcedGray) && details.forcedGray.length) {
    flags.push({ label: '灰触发', color: 'warning' })
  }
  if (Array.isArray(details?.whiteRules) && details.whiteRules.length) {
    flags.push({ label: '白名单规则', color: 'success' })
  }
  return flags
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

const constraints = [
  {
    title: 'IMSI 稀疏采样',
    points: [
      '同一设备命中间隔多为 5–8 min，小概率 10–12 min，极小概率 15 min。',
      '相邻两次命中间隔 ≤15 min 视为同一会话，大于 15 min 判定为断开。',
    ],
  },
  {
    title: 'IMSI 归属地缺失',
    points: [
      '归属地字段大量缺失，不再以本地 / 外地作硬条件，仅在可判定时叠加 +2～+6 弱权重。',
    ],
  },
  {
    title: '摄像头能力',
    points: [
      '固定枪机可做进入 / 穿越 / 停留 / 路径级行为分析，不依赖器具或热源识别。',
    ],
  },
  {
    title: '场域特性',
    points: [
      '荒野环境来人少，夜间是盗掘主窗口；踩点多在白天或黄昏且接近外围。',
    ],
  },
  {
    title: '人群背景',
    points: [
      '存在固定农事活动人员，需要模式化白名单而非一次性的静态名单。',
    ],
  },
]

const timeWindowNotes = [
  { title: '滑动窗口', detail: '30 min 与 90 min 两档并用（快速响应 + 行为沉淀）。' },
  { title: 'IMSI 会话', detail: '相邻命中 ≤15 min 视为同一会话，会话起止为首末次命中时间。' },
  { title: '摄像头会话', detail: '同一轨迹在 AOI 内的进入–离开视为一会话；10 min 内再次进入视为往返。' },
]

const timeSegments = [
  { label: '白天', range: '06:00–18:59', weight: '低权重' },
  { label: '黄昏', range: '19:00–20:59', weight: '中等权重' },
  { label: '夜间', range: '21:00–05:59', weight: '高权重' },
]

const scoreBands = [
  { level: '黑名单', range: '≥70', action: '直接报警' },
  { level: '强警戒', range: '55–69', action: '快速复核 / 夜间布控' },
  { level: '灰观察', range: '30–54', action: '标黄，进入观察队列' },
  { level: '仅记录', range: '<30', action: '仅留存日志' },
]

const scoringCategories = [
  {
    key: 'A',
    title: 'A. 时段基线',
    note: '取其一',
    items: [
      { condition: '白天', score: '+0', detail: '06:00–18:59' },
      { condition: '黄昏', score: '+10', detail: '19:00–20:59' },
      { condition: '夜间', score: '+25', detail: '21:00–05:59，高风险窗口' },
    ],
  },
  {
    key: 'B',
    title: 'B. IMSI 稀疏命中强度（同一设备、同一 30 min 窗口）',
    items: [
      { condition: '命中 1 个 5 分钟桶', score: '+2', detail: '可能过路' },
      { condition: '命中 2 个 5 分钟桶', score: '+8', detail: '约 10–16 min 停留' },
      { condition: '命中 3 个 5 分钟桶', score: '+14', detail: '约 15–24 min 停留' },
      { condition: '命中 ≥4 个 5 分钟桶', score: '+20', detail: '≥20–30 min 强停留 / 明显蹲守' },
    ],
  },
  {
    key: 'C',
    title: 'C. IMSI 时间结构（同一会话内）',
    items: [
      { condition: '会话内曾出现 >12 min 间隔', score: '+4', detail: '可能在边界徘徊 / 开关机 / 弱覆盖' },
      { condition: '>15 min 断开后 ≤2 h 内同日再现', score: '+6', detail: '疑似踩点或试探重返' },
    ],
  },
  {
    key: 'D',
    title: 'D. 摄像头（AOI）行为',
    items: [
      { condition: '白天进入 AOI', score: '+10', detail: '进入坟包 AOI' },
      { condition: '夜间进入 AOI', score: '+25', detail: '夜间进入为最强风险信号' },
      { condition: '白天停留 >60 s', score: '+8', detail: '持续停留' },
      { condition: '夜间停留 >60 s', score: '+15', detail: '夜间停留累加' },
      { condition: '夜间停留 >180 s', score: '+10', detail: '在 >60 s 基础上叠加' },
      { condition: '白天 10 min 内往返', score: '+6', detail: '快速进出侦察' },
      { condition: '夜间 10 min 内往返', score: '+12', detail: '夜间往返高度可疑' },
      { condition: '白天周界绕行 ≥2 次（30 min）', score: '+6', detail: '逼近 AOI 外缘但未穿越' },
      { condition: '夜间周界绕行 ≥2 次（30 min）', score: '+10', detail: '夜间外围游走' },
    ],
  },
  {
    key: 'E',
    title: 'E. 结伴特征（2 min 同步窗，跨传感器融合）',
    items: [
      {
        condition: 'IMSI ≥2 设备 + 摄像头 ≥2 人影（夜间）',
        score: '+15',
        detail: '多人协同行动，夜间权重最高',
      },
      {
        condition: '不同设备 7 天内 2 次结伴',
        score: '+6',
        detail: '同窗出现且同向离开，疑似同伙',
      },
    ],
  },
  {
    key: 'F',
    title: 'F. 跨日 / 踩点特征',
    items: [
      {
        condition: '7 天内 ≥2 次白天外围出现（IMSI≥2 次 /30 min 或摄像头外围徘徊 >90 s），且未入 AOI',
        score: '+10',
        detail: '反复白天踩点',
      },
      {
        condition: '踩点后 14 天内任意夜间 AOI 进入',
        score: '直接黑名单',
        detail: '无需再看分数，触发自动报警',
      },
    ],
  },
  {
    key: 'G',
    title: 'G. 归属地偶得（弱特征）',
    items: [
      { condition: '非本地 MCC/MNC（白天）', score: '+2', detail: '偶尔可得时加分' },
      { condition: '非本地 MCC/MNC（夜间）', score: '+6', detail: '夜间出现更可疑' },
    ],
  },
]

const scoringNotes = [
  '建议首版以 ≥70 黑名单、55–69 强警戒、30–54 灰观察、<30 仅记录，线上运行 2–4 周后结合现场数据校准阈值。',
  '夜间 + T_hat ≥15 min 或 T_max ≥20 min 时，即便未入 AOI 也建议至少进入强警戒。',
]

const whiteListRules = [
  {
    title: '强白名单',
    detail: '值守 / 考古 / 联勤等重点人员，绑定人、设备、时间段与区域，设定到期自动失效。',
  },
  {
    title: '模式化白名单（农事白）',
    detail:
      '近 14 天内 ≥6 天白天（07:00–18:00）在外围被见到，且从不在夜间出现、从不进入 AOI，则进入农事白。夜间命中或任何 AOI 进入立刻降级为灰并清空白名单状态。',
  },
]

const grayListRules = [
  '夜间 IMSI 命中 ≥2 次 /30 min（即 B≥8）但未入 AOI。',
  '夜间摄像头进入 AOI>60 s（即 D≥15），但单人且未往返。',
  '7 天内出现踩点特征（F +10）。',
  '黄昏（19:00–20:59）IMSI≥3 次 /30 min 或摄像头在 AOI 外缘绕行 ≥2 次。',
  '任意时段 IMSI 重返（C +6）叠加摄像头外围停留 >90 s。',
]

const blackListRules = [
  '总评分 ≥70。',
  '踩点（F）后 14 天内夜间进入 AOI。',
  '夜间结伴进入 AOI（E 夜间 + D 夜间同时满足，无论停留时长）。',
  '夜间 AOI 往返（D 夜间 +12）且 IMSI 命中 ≥2（B≥8）。',
]

const farmerWhiteNotes = [
  '以时间与空间模式识别：连续多日白天外围活动、从不触碰 AOI、离开路径一致即可判农事白。',
  '冲突优先级：夜间触发 > AOI 触发 > 结伴触发 > IMSI 强度。夜间 AOI 进入会立即压过农事白豁免。',
  '视频侧采用植被 / 光影误报抑制，AOI 使用双线框（外缘预警、内缘报警）。',
  'IMSI 去重：双卡 / eSIM 设备按同时间窗、同入点、同轨迹聚类，避免多计。',
]

const imsiStayMetrics = [
  '会话停留下界 T_min = last_seen - first_seen。',
  '停留估计 T_hat = max(T_min, (n_hits - 1) * 7 min)，适配 5–15 min 的采样间隔。',
  '停留上界 T_max = T_min + 15 min，考虑到入出场可能漏采的极端情况。',
  '夜间且 T_hat ≥15 min 或 T_max ≥20 min 时，建议直接进入强警戒。',
]

const escalationFlow = [
  '黑名单（≥70 或命中黑触发）：自动报警 + 语音驱离（若有）+ 值守电话通知 + 证据包固化（IMSI 会话、AOI 轨迹/抓拍）。',
  '强警戒（55–69）：弹窗 + 值守 30–120 s 内复核 + 夜间重点布控；14 天内再次触发灰/强警戒则升黑。',
  '灰观察（30–54）：标黄 + 数据沉淀；90 天无复触发自动清退。',
]

const ruleTableColumns = [
  { title: '规则 ID', dataIndex: 'id', key: 'id', width: 80 },
  { title: '触发条件（简化）', dataIndex: 'trigger', key: 'trigger' },
  { title: '分值', dataIndex: 'score', key: 'score', width: 120 },
  { title: '直接效应', dataIndex: 'effect', key: 'effect', width: 220 },
]

const ruleTableData = [
  { id: 'T1', trigger: '时段 = 夜间', score: '+25', effect: '—' },
  { id: 'T2', trigger: 'IMSI 命中 2 / 3 / ≥4（30 min）', score: '+8 / +14 / +20', effect: '—' },
  { id: 'T3', trigger: 'IMSI 会话含 >12 min 间隔', score: '+4', effect: '—' },
  { id: 'T4', trigger: 'IMSI 重返（≤2 h）', score: '+6', effect: '—' },
  { id: 'V1', trigger: '夜间进入 AOI', score: '+25', effect: '—' },
  { id: 'V2', trigger: '夜间 AOI 停留 >60 s / >180 s', score: '+15 / +10', effect: '—' },
  { id: 'V3', trigger: '夜间 AOI 10 min 内往返', score: '+12', effect: '—' },
  { id: 'V4', trigger: '夜间周界绕行 ≥2 次（30 min）', score: '+10', effect: '—' },
  { id: 'G1', trigger: '夜间结伴（IMSI≥2 + 摄像头≥2）', score: '+15', effect: '—' },
  { id: 'P1', trigger: '7 天踩点（白天外围≥2 次）', score: '+10', effect: '14 天内夜间入 AOI → 黑名单' },
  { id: 'W1', trigger: '农事白（14 天≥6 天白天外围、无夜/无 AOI）', score: '设为白', effect: '夜间或 AOI 进入即取消白' },
]

const scenarioExamples = [
  {
    title: '场景 A：夜间单人直奔坟包并停留 2 分钟',
    evaluation:
      'T1(+25) + V1(+25) + V2(+15) = 65；若 IMSI 恰好 2 次（+8）→ 73（黑）。即便缺 IMSI 也达 65（强警戒，可将阈值设 65 直接黑）。',
  },
  {
    title: '场景 B：白天两次外围徘徊，三天后夜间入 AOI 30 s',
    evaluation: '先触发 P1（踩点）+10，14 天内夜间入 AOI → 直接黑名单，无需再看得分。',
  },
  {
    title: '场景 C：农民白天常在外围，两周 8 天命中，未夜间出现、未进 AOI',
    evaluation: '进入农事白；若某晚 22:30 靠近 AOI 外缘，则立即降灰并持续监控。',
  },
]

const implementationNotes = [
  {
    title: 'IMSI',
    points: [
      '会话断点 15 min，30 / 90 min 滑窗并行。',
      '同窗多设备需去重，并与摄像头时间对齐（±2 min 容差）。',
    ],
  },
  {
    title: '视频',
    points: [
      'AOI 画双层框（外缘预警、内缘报警），启用入侵 / 区域停留 / 越线 / 徘徊算法。',
      '做植被 / 阴影掩膜，保留轨迹与关键帧用于取证。',
    ],
  },
  {
    title: '名单与衰减',
    points: [
      '灰名单默认 90 天，强警戒 14 天内复触发升黑。',
      '农事白滚动维持，任何夜间或 AOI 事件即时撤销。',
    ],
  },
  {
    title: '报送链路',
    points: [
      '黑事件输出证据包：IMSI 会话摘要 + 摄像头轨迹 / 截图 + 判定规则命中清单。',
    ],
  },
]

const classificationSteps = [
  '若命中强白名单或临时白名单且在授权时间 / 区域内 → 白名单跳过。',
  '若满足农事白模式则临时豁免；一旦夜间或 AOI 事件触发即撤销豁免并重新评分。',
  '按规则累加评分（30 min 事件窗口），同时时段、IMSI、摄像头、群体、踩点及弱特征叠加。',
  '若任一直接黑条件满足 → 黑名单报警。',
  '否则按总分判断：≥70 黑；≥55 强警戒；≥30 灰；其余仅记录。',
  '若得分未达 30，但命中灰名单强制条件（夜间徘徊、踩点等）也进入灰名单。',
  '灰 / 强警戒存在衰减与升级逻辑：14 天 / 90 天窗口内复触发时升级，超时未复触发则降级或清退。',
]

const grayForceConditions = [
  '夜间 IMSI ≥2 次 /30 min 且未入 AOI。',
  '夜间单人 AOI 停留 >60 s，未往返。',
  '触发昼间踩点（DAYTIME_SCOUTING）。',
  '黄昏绕行或 IMSI ≥3 次。',
  'IMSI 重返 + 外围停留 >90 s。',
]

const directBlackConditions = [
  '近 14 天内曾触发昼间踩点且夜间进入 AOI。',
  '夜间进入 AOI 且群体规模 ≥2（IMSI 或摄像头确认）。',
  '夜间进入 AOI 且 10 min 内再次进入（往返）。',
]

const riskModelSpec = {
  version: '0.1',
  description:
    'Perimeter anti-looting logic for burial site. IMSI scanner (300m outer ring, sparse 5-15min hits) + fixed bullet camera on mound AOI (30-50m). Night activity is primary risk window.',
  time_windows: {
    session_break_minutes: 15,
    short_window_minutes: 30,
    long_window_minutes: 90,
    revisit_max_gap_hours: 2,
    dawn_day_dusk_night: {
      day: { start: '06:00', end: '18:59' },
      dusk: { start: '19:00', end: '20:59' },
      night: { start: '21:00', end: '05:59' },
    },
  },
  thresholds: {
    score_strong_black: 70,
    score_strong_alert: 55,
    score_gray: 30,
    gray_observation_days: 90,
    strong_alert_escalation_days: 14,
    farmer_white_observation_days: 14,
    farmer_white_min_day_presence: 6,
  },
  lists: {
    strong_whitelist: {
      description: 'Permanent staff / guards / archaeology team / law enforcement',
      binding: [
        'person_identity',
        'device_id_or_imsi_or_esim_profile',
        'allowed_time_window',
        'allowed_zone',
      ],
      auto_expire_months: 6,
    },
    temporary_whitelist: {
      description: 'Contractors / temporary workers / survey crew',
      binding: [
        'person_identity',
        'device_id_or_imsi_or_esim_profile',
        'allowed_time_window',
        'allowed_zone',
      ],
      auto_expire_on_end_date: true,
    },
    farmer_white: {
      description:
        'Locals doing repeated daytime farm work near perimeter but never entering AOI and never appearing at night',
      entry_rule: {
        window_days: 14,
        min_daytime_days_present: 6,
        conditions: ['no_night_presence', 'no_AOI_entry'],
      },
      auto_revoke_if: ['night_presence_detected', 'AOI_entry_detected_any_time'],
    },
  },
  scoring: {
    time_of_day: [
      { id: 'TOD_DAY', condition: 'event_in_day_window', score: 0, description: '06:00-18:59' },
      { id: 'TOD_DUSK', condition: 'event_in_dusk_window', score: 10, description: '19:00-20:59' },
      {
        id: 'TOD_NIGHT',
        condition: 'event_in_night_window',
        score: 25,
        description: '21:00-05:59 high risk',
      },
    ],
    imsi_activity_strength: [
      {
        id: 'IMSI_HITS_1',
        condition: 'same_device_seen_1_time_in_30min_window',
        score: 2,
        description: 'likely just passing through',
      },
      {
        id: 'IMSI_HITS_2',
        condition: 'same_device_seen_2_times_in_30min_window',
        score: 8,
        description: '≈10-16min linger given 5-8min sampling gap',
      },
      {
        id: 'IMSI_HITS_3',
        condition: 'same_device_seen_3_times_in_30min_window',
        score: 14,
        description: '≈15-24min linger',
      },
      {
        id: 'IMSI_HITS_4PLUS',
        condition: 'same_device_seen_4_or_more_times_in_30min_window',
        score: 20,
        description: '≈20-30+min linger',
      },
    ],
    imsi_session_structure: [
      {
        id: 'IMSI_LONG_GAP_IN_SESSION',
        condition: 'within_one_session_any_gap_over_12min',
        score: 4,
        description: 'possible circling/coverage edge/power-cycle',
      },
      {
        id: 'IMSI_REVISIT_SOON',
        condition: 'session_break_over_15min_and_same_device_returns_within_2h_same_day',
        score: 6,
        description: 'come-back-within-2h suggests probing',
      },
    ],
    camera_AOI_behavior: [
      {
        id: 'AOI_ENTRY_DAY',
        condition: 'target_enters_AOI_daytime',
        score: 10,
        description: 'entered mound AOI during day',
      },
      {
        id: 'AOI_ENTRY_NIGHT',
        condition: 'target_enters_AOI_nighttime',
        score: 25,
        description: 'entered mound AOI during night (critical)',
      },
      {
        id: 'AOI_STAY_DAY',
        condition: 'target_stays_in_AOI_over_60s_daytime',
        score: 8,
        description: 'lingering >60s daytime',
      },
      {
        id: 'AOI_STAY_NIGHT_SHORT',
        condition: 'target_stays_in_AOI_over_60s_nighttime',
        score: 15,
        description: 'lingering >60s nighttime',
      },
      {
        id: 'AOI_STAY_NIGHT_LONG',
        condition: 'target_stays_in_AOI_over_180s_nighttime',
        score: 10,
        description: 'extra add if >180s at night, stacks on AOI_STAY_NIGHT_SHORT',
      },
      {
        id: 'AOI_REENTER_WITHIN_10MIN_DAY',
        condition: 'same_target_reenters_AOI_within_10min_daytime',
        score: 6,
        description: 'daytime quick in/out scouting',
      },
      {
        id: 'AOI_REENTER_WITHIN_10MIN_NIGHT',
        condition: 'same_target_reenters_AOI_within_10min_nighttime',
        score: 12,
        description: 'nighttime in/out checking surroundings',
      },
    ],
    camera_perimeter_behavior: [
      {
        id: 'AOI_PERIM_LOOP_DAY',
        condition: 'target_circles_AOI_perimeter_2plus_times_in_30min_daytime_without_entry',
        score: 6,
        description: 'circling mound but not yet entering (day)',
      },
      {
        id: 'AOI_PERIM_LOOP_NIGHT',
        condition: 'target_circles_AOI_perimeter_2plus_times_in_30min_nighttime_without_entry',
        score: 10,
        description: 'circling mound perimeter at night',
      },
    ],
    group_behavior: [
      {
        id: 'GROUP_NIGHT',
        condition:
          '>=2_distinct_devices_detected_same_2min_window AND >=2_humans_seen_by_camera_same_2min_window AND time_is_night',
        score: 15,
        description: 'multiple people together at night',
      },
      {
        id: 'REPEATED_PAIRING',
        condition: 'two_devices_seen_together_same_direction_entry_exit_at_least_2_times_in_7days',
        score: 6,
        description: 'recurrent pairing / working as a team',
      },
    ],
    casing_activity: [
      {
        id: 'DAYTIME_SCOUTING',
        condition:
          'within_7days_target_detected_daytime_or_dusk_near_perimeter_on_2_or_more_days_with_imsi_hits_>=2_per_30min_or_camera_perimeter_stay_over_90s AND never_enters_AOI',
        score: 10,
        description: 'repeated daylight scouting / casing',
      },
    ],
    origin_hint_optional: [
      {
        id: 'NON_LOCAL_AT_NIGHT',
        condition: 'imsi_mcc_mnc_indicates_non_local AND time_is_night',
        score: 6,
        description: 'only if MCC/MNC is known; most of the time unknown so score 0',
      },
      {
        id: 'NON_LOCAL_DAY',
        condition: 'imsi_mcc_mnc_indicates_non_local AND time_is_day',
        score: 2,
        description: 'weak daylight signal',
      },
    ],
  },
  gray_entry_conditions: {
    description:
      'Conditions that force an entity into GRAY watchlist (observation) even if total score is below 55.',
    rules: [
      {
        id: 'GRAY_NIGHT_IMSI_LINGER',
        condition: 'time_is_night AND same_device_seen_2plus_times_in_30min_window AND NOT_entered_AOI',
        action: 'ADD_TO_GRAY',
        notes: 'Night linger outside AOI is suspicious even without video confirmation.',
      },
      {
        id: 'GRAY_NIGHT_AOI_BRIEF',
        condition: 'time_is_night AND target_enters_AOI AND AOI_stay_over_60s AND single_person_only AND NOT_reenter_within_10min',
        action: 'ADD_TO_GRAY',
        notes: 'Single intruder at mound at night, short stay.',
      },
      {
        id: 'GRAY_SCOUTING',
        condition: 'DAYTIME_SCOUTING rule triggered',
        action: 'ADD_TO_GRAY',
        notes: 'Daytime/dusk perimeter scouting over multiple days.',
      },
      {
        id: 'GRAY_DUSK_LOOP',
        condition: 'time_is_dusk AND (AOI_PERIM_LOOP_DAY OR IMSI_HITS_3)',
        action: 'ADD_TO_GRAY',
        notes: 'Hanging around mound boundary at 19:00-20:59.',
      },
      {
        id: 'GRAY_RETURN_MATCH',
        condition: 'IMSI_REVISIT_SOON AND camera_perimeter_stay_over_90s_same_period',
        action: 'ADD_TO_GRAY',
        notes: 'Device leaves >15min then comes back within 2h and is visually lingering.',
      },
    ],
  },
  direct_black_conditions: {
    description: 'Any of these means immediate BLACK (alarm), regardless of score.',
    rules: [
      {
        id: 'BLACK_SCOUT_THEN_NIGHT_ENTRY',
        condition: 'entity_triggered_DAYTIME_SCOUTING_within_past_14_days AND now_time_is_night AND target_enters_AOI_any_duration',
        action: 'RAISE_BLACK_ALERT',
        notes: "Classic 'casing by day, dig by night' pattern.",
      },
      {
        id: 'BLACK_NIGHT_GROUP_IN_AOI',
        condition: 'time_is_night AND target_enters_AOI AND group_size_>=2 (IMSI or camera confirms >=2 persons)',
        action: 'RAISE_BLACK_ALERT',
        notes: 'Multiple intruders at the mound at night.',
      },
      {
        id: 'BLACK_NIGHT_REENTER',
        condition: 'time_is_night AND target_enters_AOI AND reenters_AOI_within_10min',
        action: 'RAISE_BLACK_ALERT',
        notes: 'Nighttime in/out/in suggests active probing of burial point.',
      },
    ],
  },
  classification_logic: {
    description: 'How final state (WHITE / GRAY / STRONG_ALERT / BLACK) is chosen per entity per incident.',
    steps: [
      'STEP 1: If entity in strong_whitelist or temporary_whitelist and current time is within allowed_time_window and inside allowed_zone -> CLASSIFY = WHITE_OVERRIDE (no alarm, just log).',
      'STEP 2: If entity qualifies as farmer_white (pattern: >=6 daytime appearances in last 14 days, only daytime/dusk, never night, never AOI) -> CLASSIFY = WHITE_FARM. BUT if any night presence or any AOI entry happens now, immediately revoke farmer_white and continue.',
      'STEP 3: Compute total_score = sum of all matching scoring rules for this 30min incident window (time_of_day + imsi_activity_strength + imsi_session_structure + camera_AOI_behavior + camera_perimeter_behavior + group_behavior + casing_activity + origin_hint_optional).',
      'STEP 4: Check direct_black_conditions. If any TRUE -> CLASSIFY = BLACK (alarm).',
      'STEP 5: Else if total_score >= score_strong_black (>=70) -> CLASSIFY = BLACK (alarm).',
      'STEP 6: Else if total_score >= score_strong_alert (>=55) -> CLASSIFY = STRONG_ALERT (treat operationally close to BLACK: immediate voice warning / call-out / push to human).',
      'STEP 7: Else if total_score >= score_gray (>=30) -> CLASSIFY = GRAY (watchlist).',
      'STEP 8: Else apply gray_entry_conditions: if any TRUE -> CLASSIFY = GRAY.',
      'STEP 9: Else -> CLASSIFY = LOG_ONLY (no alert).',
    ],
  },
  escalation_and_decay: {
    gray_watchlist: {
      default_retention_days: 90,
      escalate_to_strong_alert_if: {
        condition: 'same_entity_triggers_GRAY_again_within_14_days OR total_score >= 55',
        action: 'UPGRADE_TO_STRONG_ALERT',
      },
      decay_rule: 'If no new gray/strong_alert/black triggers for 90 days, remove entity from gray_watchlist.',
    },
    strong_alert: {
      escalate_to_black_if: {
        condition: 'entity_hits_strong_alert_twice_within_14_days OR any_direct_black_condition_true',
        action: 'UPGRADE_TO_BLACK',
      },
      decay_rule: 'If 14 days pass with no new incidents, downgrade to gray_watchlist for remainder of 90-day window.',
    },
    farmer_white: {
      entry_condition: 'see lists.farmer_white.entry_rule',
      auto_revoke_conditions: ['any_nighttime_presence_detected', 'any_AOI_entry_detected'],
      post_revoke_state: 'Upon revoke, entity is evaluated normally (can become GRAY/ALERT/BLACK).',
    },
  },
  helper_calculations: {
    imsi_session_definition: {
      description: "How we group sparse IMSI hits into a 'session'.",
      same_session_if_gap_minutes_lte: 15,
    },
    imsi_presence_estimation: {
      description: 'Lower bound, mid estimate, and upper bound for how long device stayed, given sparse (5-15min) hits.',
      T_min: 'last_seen_timestamp - first_seen_timestamp',
      T_hat: 'max(T_min, (num_hits-1)*7min)',
      T_max: 'T_min + 15min',
      night_risk_rule: 'If time_is_night AND (T_hat >= 15min OR T_max >= 20min) -> force STRONG_ALERT even without AOI entry',
    },
    multi_device_grouping: {
      description: 'We try not to double-count one person with two SIMs/eSIMs.',
      merge_keys: ['first_seen_time_bucket_5min', 'approx_entry_point_sector', 'camera_track_id_if_available'],
      result: "treat merged cluster as one 'entity' for scoring/group_behavior",
    },
  },
}

const specJson = JSON.stringify(riskModelSpec, null, 2)
</script>

<template>
  <div class="risk-model">
    <header class="page-header">
      <h1>风控模型总览</h1>
      <p>墓地盗掘防控场景的 IMSI + 枪机联合风控逻辑，覆盖评分、名单、处置与工程实现要点。</p>
    </header>

    <section class="live-section">
      <h2>实时风险态势</h2>
      <p class="section-note">基于最近 90 分钟内的 IMSI / 摄像头 / 雷达数据实时评分与名单判定。</p>
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
                        <span class="risk-item-subject">{{ subjectLabel(item.subjectType) }} · {{ item.subjectKey }}</span>
                        <span class="risk-item-score">{{ formatScore(item.score) }}</span>
                      </div>
                      <div v-if="item.summary" class="risk-item-summary">{{ item.summary }}</div>
                      <div v-if="extractTopRule(item)" class="risk-item-rule">{{ extractTopRule(item) }}</div>
                      <div v-if="extractFlags(item).length" class="risk-item-flags">
                        <a-tag
                          v-for="flag in extractFlags(item)"
                          :key="flag.label"
                          :color="flag.color"
                        >
                          {{ flag.label }}
                        </a-tag>
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
                  <strong>{{ subjectLabel(item.subjectType) }} · {{ item.subjectKey }}</strong>
                  <span v-if="item.summary" class="risk-log-summary">{{ item.summary }}</span>
                </div>
                <div class="risk-log-time">{{ formatTime(item.updatedAt) }}</div>
              </a-list-item>
            </template>
          </a-list>
        </a-collapse-panel>
      </a-collapse>
    </section>

    <section>
      <h2>0）现场约束 → 规则设计要点</h2>
      <div class="card-grid">
        <a-card v-for="item in constraints" :key="item.title" :title="item.title">
          <ul class="bullet-list">
            <li v-for="point in item.points" :key="point">{{ point }}</li>
          </ul>
        </a-card>
      </div>
    </section>

    <section>
      <h2>1）事件模型与时间窗</h2>
      <div class="card-grid">
        <a-card v-for="item in timeWindowNotes" :key="item.title" :title="item.title">
          <p>{{ item.detail }}</p>
        </a-card>
      </div>
      <a-table
        class="compact-table"
        size="small"
        :columns="[
          { title: '时段', dataIndex: 'label', key: 'label' },
          { title: '时间范围', dataIndex: 'range', key: 'range' },
          { title: '权重提示', dataIndex: 'weight', key: 'weight' },
        ]"
        :data-source="timeSegments"
        :pagination="false"
        row-key="label"
      />
    </section>

    <section>
      <h2>2）稀疏友好风险评分（0–100）</h2>
      <a-table
        class="compact-table"
        size="small"
        :columns="[
          { title: '等级', dataIndex: 'level', key: 'level' },
          { title: '分数区间', dataIndex: 'range', key: 'range' },
          { title: '处置建议', dataIndex: 'action', key: 'action' },
        ]"
        :data-source="scoreBands"
        :pagination="false"
        row-key="level"
      />
      <ul class="bullet-list section-note">
        <li v-for="note in scoringNotes" :key="note">{{ note }}</li>
      </ul>
      <div class="card-stack">
        <a-card v-for="cat in scoringCategories" :key="cat.key" :title="cat.title">
          <p v-if="cat.note" class="card-note">{{ cat.note }}</p>
          <a-table
            size="small"
            class="nested-table"
            :columns="[
              { title: '触发条件', dataIndex: 'condition', key: 'condition' },
              { title: '分值', dataIndex: 'score', key: 'score', width: 120 },
              { title: '说明', dataIndex: 'detail', key: 'detail' },
            ]"
            :data-source="cat.items"
            :pagination="false"
            :row-key="(record) => `${cat.key}-${record.condition}`"
          />
        </a-card>
      </div>
    </section>

    <section>
      <h2>3）名单规则（结合评分阈值）</h2>
      <div class="card-grid">
        <a-card v-for="item in whiteListRules" :key="item.title" :title="item.title">
          <p>{{ item.detail }}</p>
        </a-card>
      </div>
      <a-card title="灰名单触发条件（任一满足）" class="mt16">
        <ul class="bullet-list">
          <li v-for="rule in grayListRules" :key="rule">{{ rule }}</li>
        </ul>
      </a-card>
      <a-card title="黑名单（直接报警）" class="mt16">
        <ul class="bullet-list">
          <li v-for="rule in blackListRules" :key="rule">{{ rule }}</li>
        </ul>
      </a-card>
    </section>

    <section>
      <h2>4）“农民/熟客”与误报抑制</h2>
      <ul class="bullet-list">
        <li v-for="note in farmerWhiteNotes" :key="note">{{ note }}</li>
      </ul>
    </section>

    <section>
      <h2>5）稀疏 IMSI 下的停留时长估计</h2>
      <ul class="bullet-list">
        <li v-for="metric in imsiStayMetrics" :key="metric">{{ metric }}</li>
      </ul>
    </section>

    <section>
      <h2>6）处置分级与联动</h2>
      <ul class="bullet-list">
        <li v-for="item in escalationFlow" :key="item">{{ item }}</li>
      </ul>
    </section>

    <section>
      <h2>7）规则表（示例）</h2>
      <a-table
        class="compact-table"
        size="small"
        :columns="ruleTableColumns"
        :data-source="ruleTableData"
        :pagination="false"
        row-key="id"
      />
    </section>

    <section>
      <h2>8）典型场景判定示例</h2>
      <div class="card-stack">
        <a-card v-for="scene in scenarioExamples" :key="scene.title" :title="scene.title">
          <p>{{ scene.evaluation }}</p>
        </a-card>
      </div>
    </section>

    <section>
      <h2>9）实施要点（工程配置）</h2>
      <div class="card-stack">
        <a-card v-for="block in implementationNotes" :key="block.title" :title="block.title">
          <ul class="bullet-list">
            <li v-for="point in block.points" :key="point">{{ point }}</li>
          </ul>
        </a-card>
      </div>
    </section>

    <section>
      <h2>判定与升级逻辑梳理</h2>
      <a-card title="分类步骤">
        <ul class="bullet-list">
          <li v-for="step in classificationSteps" :key="step">{{ step }}</li>
        </ul>
      </a-card>
      <div class="card-grid mt16">
        <a-card title="灰名单强制条件">
          <ul class="bullet-list">
            <li v-for="cond in grayForceConditions" :key="cond">{{ cond }}</li>
          </ul>
        </a-card>
        <a-card title="直接黑名单条件">
          <ul class="bullet-list">
            <li v-for="cond in directBlackConditions" :key="cond">{{ cond }}</li>
          </ul>
        </a-card>
      </div>
    </section>

    <section>
      <h2>机器可读配置（参考）</h2>
      <p class="section-note">下方 JSON 便于后端或策略引擎直接引用，可作为版本 0.1 的风控模型基线。</p>
      <a-collapse>
        <a-collapse-panel key="spec" header="展开查看 JSON 配置">
          <pre class="json-view">{{ specJson }}</pre>
        </a-collapse-panel>
      </a-collapse>
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

section {
  margin-bottom: 32px;
}

.live-section .section-note {
  margin-top: 0;
  color: rgba(255, 255, 255, 0.65);
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

.risk-item-subject {
  font-weight: 600;
  color: var(--text-color);
  word-break: break-word;
}

.risk-item-score {
  font-weight: 600;
  font-size: 20px;
  color: #ff7875;
}

.risk-column-strong_alert .risk-item-score {
  color: #fadb14;
}

.risk-column-gray .risk-item-score {
  color: #69c0ff;
}

.risk-column-white .risk-item-score {
  color: #73d13d;
}

.risk-item-summary {
  color: rgba(255, 255, 255, 0.8);
}

.risk-item-rule {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.65);
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

.card-stack {
  display: grid;
  gap: 16px;
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

.json-view {
  margin: 0;
  padding: 16px;
  max-height: 480px;
  overflow: auto;
  background: rgba(0, 0, 0, 0.75);
  color: var(--text-color);
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  line-height: 1.5;
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
