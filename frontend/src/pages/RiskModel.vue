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
  BLACK: { label: '黑名单', tag: 'error', empty: '暂无黑名单事件', description: '评分 ≥70 或命中 F4/F3 夜间成伙等黑名单触发' },
  STRONG_ALERT: { label: '强警戒', tag: 'warning', empty: '暂无强警戒目标', description: '评分 55–69，或命中 F5 夜外圈强警等强警戒条件' },
  GRAY: { label: '灰观察', tag: 'processing', empty: '暂无灰名单目标', description: '评分 30–54，或夜间 AOI 短停 + 无手机等灰名单条件' },
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

function extractScoreBreakdown(item: RiskAssessment): Array<{ id: string; score: number; description: string }> {
  const details: any = item.details
  const scoreHits = Array.isArray(details?.scoreHits) ? details.scoreHits : []
  return scoreHits
    .map((hit: any, index: number) => ({
      id: typeof hit?.id === 'string' && hit.id ? hit.id : `rule-${index}`,
      score: Number(hit?.score ?? 0),
      description: typeof hit?.description === 'string' ? hit.description : '',
    }))
    .filter((hit) => Number.isFinite(hit.score) && hit.score !== 0)
}

function extractFlags(item: RiskAssessment): Array<{ label: string; color: string }> {
  const details: any = item.details
  const flags: Array<{ label: string; color: string }> = []
  if (Array.isArray(details?.directBlack) && details.directBlack.length) {
    flags.push({ label: '黑触发', color: 'error' })
  }
  if (Array.isArray(details?.strongAlertRules) && details.strongAlertRules.length) {
    flags.push({ label: '强警触发', color: 'orange' })
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

const eventAlignmentColumns = [
  { title: '名称', dataIndex: 'name', key: 'name', width: 180 },
  { title: '定义', dataIndex: 'definition', key: 'definition' },
  { title: '默认值/建议', dataIndex: 'defaultValue', key: 'defaultValue', width: 200 },
  { title: '作用', dataIndex: 'purpose', key: 'purpose' },
]

const eventAlignmentRows = [
  {
    name: '事件窗口（incident_window）',
    definition: '一次判定所覆盖的时间片',
    defaultValue: '30 分钟滑窗（每 1 分钟平移）',
    purpose: '对齐两传感器的“这段时间到底发生了什么”',
  },
  {
    name: '围栏去重',
    definition: 'Burst 合并（90s）、5 分钟分桶、15 分钟会话断点',
    defaultValue: '90s / 5min / 15min',
    purpose: '去除重复条，沿用既有口径',
  },
  {
    name: '紧邻同窗（tight）',
    definition: '摄像头事件时间点 ±5 分钟内的围栏统计',
    defaultValue: '±5 分钟',
    purpose: '快速确认同窗是否有设备到达/聚集',
  },
  {
    name: '宽松同窗（loose）',
    definition: '摄像头事件时间点 ±10 分钟内的围栏统计',
    defaultValue: '±10 分钟',
    purpose: '纳入稍滞后/提前的到达峰值',
  },
  {
    name: '到达数（arrivals_10m）',
    definition: '近 10 分钟首次出现的唯一设备数',
    defaultValue: '10 分钟窗口',
    purpose: '关注“新进来的人”而非总量',
  },
  {
    name: '到达异常（arrival_z）',
    definition: '近 10 分钟到达数的 z 分数（对比历史同时段夜/昼基线）',
    defaultValue: '阈值 z≥2',
    purpose: '发现“今天不对劲，比平时多很多”',
  },
  {
    name: '唯一桶数（bucket_count）',
    definition: '30 分钟内有命中的 5 分钟桶数量',
    defaultValue: '0–6',
    purpose: '更稳健地代替“次数”，避免重复统计',
  },
]

const fusionColumns = [
  { title: '编码', dataIndex: 'code', key: 'code', width: 120 },
  { title: '条件（摄像头事件与围栏时序对齐）', dataIndex: 'condition', key: 'condition' },
  { title: '白天 +分', dataIndex: 'dayScore', key: 'dayScore', width: 120 },
  { title: '夜间 +分', dataIndex: 'nightScore', key: 'nightScore', width: 140 },
  { title: '说明', dataIndex: 'note', key: 'note' },
]

const fusionRows = [
  {
    code: 'F1 同窗协同',
    condition: '摄像头进入 AOI，且 loose(±10m) 内 arrivals_10m ≥ 2 或 arrival_z ≥ 2',
    dayScore: '+8',
    nightScore: '+15',
    note: '夜里“进点”同时有新设备涌入/明显异常 = 协同作案迹象',
  },
  {
    code: 'F2 无手机嫌疑',
    condition: '摄像头进入 AOI，且 tight(±5m) 内 arrivals_10m = 0 且站点检出率高',
    dayScore: '+2',
    nightScore: '+12',
    note: '夜里进点没有任何新设备迹象 → 可能关机/留机/无手机',
  },
  {
    code: 'F3 成伙协同',
    condition: '摄像头画面 ≥2 人，且 tight(±5m) 内 arrivals_10m ≥ 2',
    dayScore: '+6',
    nightScore: '+15',
    note: '两边都显示“多人同窗”',
  },
  {
    code: 'F4 踩点→返场',
    condition: '7 天内出现白天/傍晚外围踩线，且 14 天内夜间任意一次进入 AOI',
    dayScore: '—',
    nightScore: '直接黑（触发黑名单）',
    note: '踩点→夜返，无需再看分数',
  },
  {
    code: 'F5 夜外圈强警',
    condition: '夜间未见摄像头事件，但围栏 30 分钟内 bucket_count ≥ 3 或 T_hat ≥ 15min 或 T_max ≥ 20min',
    dayScore: '—',
    nightScore: '强警（升级强警戒）',
    note: '外圈蹲守，先行喝止/布控',
  },
  {
    code: 'F6 夜异常汇聚',
    condition: '夜间，loose 内 arrival_z ≥ 3，且任意摄像头外围徘徊/靠近',
    dayScore: '+4',
    nightScore: '+10',
    note: '极端“车人涌入”背景下的靠近更可疑',
  },
]

const decisionColumns = [
  { title: '等级', dataIndex: 'level', key: 'level', width: 120 },
  { title: '触发（任意满足）', dataIndex: 'triggers', key: 'triggers' },
  { title: '处置', dataIndex: 'actions', key: 'actions' },
]

const decisionRows = [
  {
    level: '黑名单',
    triggers: '① 总分 ≥ 70；② F4（踩点→夜返）；③ 夜间成伙进入 AOI（摄像头≥2 人且 F3 达标）；④ 夜间往返进入 AOI（沿用原规则）',
    actions: '立刻报警、语音驱离、通知值守、取证包',
  },
  {
    level: '强警戒',
    triggers: '① 总分 55–69；② F5（夜外圈强警）；③ F1 协同 + AOI 停留 >60s 且人数单一',
    actions: '立即关注、喊话、近距离巡查',
  },
  {
    level: '灰名单',
    triggers: '① 总分 30–54；② 夜间单次 AOI 短停 + F2 无手机嫌疑；③ 多日晚间外围绕行 + arrival_z ≥ 2',
    actions: '90 天观察、复触发上调',
  },
]

const scenarioColumns = [
  { title: '场景', dataIndex: 'scenario', key: 'scenario' },
  { title: '摄像头', dataIndex: 'camera', key: 'camera' },
  { title: '围栏', dataIndex: 'perimeter', key: 'perimeter' },
  { title: '结论', dataIndex: 'conclusion', key: 'conclusion' },
]

const scenarioRows = [
  {
    scenario: 'A 夜间单人进 AOI 停留 2 分钟，同窗无新设备',
    camera: '进 AOI + 停留 >60s',
    perimeter: 'arrivals_10m = 0（tight）',
    conclusion: 'AOI(+25)+停留(+15)+F2(+12)+夜间基分(+25)=77 → 黑名单',
  },
  {
    scenario: 'B 夜间两人进 AOI，同窗有 3 台新设备',
    camera: '进 AOI + ≥2 人',
    perimeter: 'arrivals_10m = 3（tight）',
    conclusion: 'AOI(+25)+F3(+15)+F1(+15)+夜间(+25)=80 → 黑名单',
  },
  {
    scenario: 'C 夜间未进 AOI，外圈同一设备 30 分钟≥3 桶',
    camera: '无',
    perimeter: 'bucket_count ≥ 3（30 分钟）',
    conclusion: 'F5 触发 → 强警戒（喊退 + 布控）',
  },
  {
    scenario: 'D 白天两次外围绕行 + 7 天内多次“到达”',
    camera: '外围绕行 ≥2 次',
    perimeter: 'arrival_z ≥ 2',
    conclusion: '灰名单；若 14 天内夜进 AOI → F4 直接黑',
  },
]

const engineeringGuidelines = [
  '只在时间同窗做加权，不做身份绑定：摄像头看到的人未必携机，允许“无手机”成为可疑信号（F2）。',
  '分别建设摄像头与围栏基线：AOI 进入/停留/人数 vs arrivals_10m、arrival_z、bucket_count、会话重返，融合时仅用 tight/loose 同窗加权。',
  'site_detectability_baseline 持续自校正，只有高可检出站点才启用 F2 的强加分，避免弱覆盖点误判。',
  '多站点可选项：若围栏有扇区/多点接收，可进一步加上方向同窗的小权重；没有就跳过。',
]

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
                      <div v-if="extractFlags(item).length" class="risk-item-flags">
                        <a-tag
                          v-for="flag in extractFlags(item)"
                          :key="flag.label"
                          :color="flag.color"
                        >
                          {{ flag.label }}
                        </a-tag>
                      </div>
                      <div v-if="extractScoreBreakdown(item).length" class="risk-item-breakdown">
                        <ul>
                          <li v-for="hit in extractScoreBreakdown(item)" :key="hit.id">
                            <span class="rule-desc">{{ hit.description || hit.id }}</span>
                            <span class="rule-score">{{ hit.score }} 分</span>
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
      <h2>事件级融合思路</h2>
      <div class="intro-block">
        <p>好问题：<strong>两套传感器彼此独立、无法做“同一人”的硬绑定</strong> 时，就不要做“身份级融合”，而是做 <strong>事件级融合</strong>。</p>
        <p>把某一段时间内、某一地点周边发生的 <strong>摄像头事件</strong> 与 <strong>围栏强度/到达峰值</strong> 做 <strong>时序对齐</strong>，用“是否同窗发生”“是否出现反常到达”“是否出现‘无手机’迹象”等 <strong>场景证据</strong> 加权。</p>
        <p>下面给出的表格与 JSON 增量用于在原有评分/名单框架上叠加事件级融合逻辑，可按需组合到现行规则中。</p>
      </div>
    </section>

    <section>
      <h2>表 1｜时空对齐与统计口径（事件级）</h2>
      <a-table
        class="compact-table"
        size="small"
        :columns="eventAlignmentColumns"
        :data-source="eventAlignmentRows"
        :pagination="false"
        row-key="name"
      />
    </section>

    <section>
      <h2>表 2｜融合加分项（事件级，无需身份绑定）</h2>
      <p class="section-note">这些是新增 / 调整的“融合项”，在原来的 IMSI 强度 + 摄像头行为评分基础上叠加使用。</p>
      <a-table
        class="compact-table"
        size="small"
        :columns="fusionColumns"
        :data-source="fusionRows"
        :pagination="false"
        row-key="code"
      />
    </section>

    <section>
      <h2>表 3｜最终判定（结合既有阈值）</h2>
      <a-table
        class="compact-table"
        size="small"
        :columns="decisionColumns"
        :data-source="decisionRows"
        :pagination="false"
        row-key="level"
      />
    </section>

    <section>
      <h2>表 4｜典型场景矩阵</h2>
      <a-table
        class="compact-table"
        size="small"
        :columns="scenarioColumns"
        :data-source="scenarioRows"
        :pagination="false"
        row-key="scenario"
      />
    </section>

    <section>
      <h2>工程要点（避免强行绑定“同一人”）</h2>
      <ul class="bullet-list">
        <li v-for="note in engineeringGuidelines" :key="note">{{ note }}</li>
      </ul>
    </section>

    <section>
      <p class="section-note">
        以下保留 0.1 版的规则骨架与处置说明，可与上方事件级融合指标结合使用：在同一 30 分钟事件窗口内，先按原有分层模型打底，再叠加紧邻/宽松同窗、到达峰值与无手机等融合证据。
      </p>
    </section>

    <section>
      <h2>0）现场约束 → 规则设计要点</h2>
      <div class="card-grid repeated-block">
        <a-card v-for="item in constraints" :key="`constraint-${item.title}`" :title="item.title">
          <ul class="bullet-list">
            <li v-for="point in item.points" :key="`constraint-${item.title}-${point}`">{{ point }}</li>
          </ul>
        </a-card>
      </div>
    </section>

    <section>
      <h2>1）事件模型与时间窗</h2>
      <div class="card-grid repeated-block">
        <a-card v-for="item in timeWindowNotes" :key="`tw-${item.title}`" :title="item.title">
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
      <div class="card-stack repeated-block">
        <a-card v-for="cat in scoringCategories" :key="`cat-${cat.key}`" :title="cat.title">
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
      <div class="card-grid repeated-block">
        <a-card v-for="item in whiteListRules" :key="`wh-${item.title}`" :title="item.title">
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
      <div class="card-stack repeated-block">
        <a-card v-for="scene in scenarioExamples" :key="`scene-${scene.title}`" :title="scene.title">
          <p>{{ scene.evaluation }}</p>
        </a-card>
      </div>
    </section>

    <section>
      <h2>9）实施要点（工程配置）</h2>
      <div class="card-stack repeated-block">
        <a-card v-for="block in implementationNotes" :key="`impl-${block.title}`" :title="block.title">
          <ul class="bullet-list">
            <li v-for="point in block.points" :key="`impl-${block.title}-${point}`">{{ point }}</li>
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
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.8);
}

.risk-item-breakdown .rule-desc {
  flex: 1;
  padding-right: 8px;
}

.risk-item-breakdown .rule-score {
  font-weight: 600;
  color: #ffd666;
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
