<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { message } from 'ant-design-vue'
import VideoPlayer from '@/components/VideoPlayer.vue'

// 基础连接参数（与单摄像头页一致）
import { nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort, portCount, detectMain, detectSub } from '@/store/config'

// HLS URL 缓存（按具体 id，如 cam401）
const urls = ref<Record<string,string>>({})
const loading = ref(false)
// 摄像头条目（每端口一个）
type CamStatus = 'detecting'|'ok'|'none'|'error'
type CamEntry = { port: number; selected: '01'|'02'; hasSub: boolean; hasMain: boolean; idSub: string; idMain: string; url?: string; status: CamStatus; err?: string }
const cams = ref<CamEntry[]>([])

async function startOne(id: string, silent = false, timeoutMs = 15000, pollMs = 800): Promise<boolean> {
  const ch = (id.match(/\d+/)?.[0]) || '401'
  const rtsp = `rtsp://${nvrUser.value}:${encodeURIComponent(nvrPass.value)}@${nvrHost.value}:554/Streaming/Channels/${ch}`
  try {
    const resp = await fetch(`/api/streams/${id}/start`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `rtspUrl=${encodeURIComponent(rtsp)}`
    })
    if (!resp.ok) throw new Error(`API ${resp.status}`)
    let data: any = {}
    try { data = await resp.json() } catch (_) {}
    const path = (data && data.hls) ? data.hls : `/streams/${id}/index.m3u8`
    const normalized = path.startsWith('/') ? path : `/${path}`
    const ok = await waitUntilReady(id, timeoutMs, pollMs)
    if (ok) {
      urls.value = { ...urls.value, [id]: normalized }
    } else if (!silent) {
      message.warning(`${id} 清单未就绪，尝试播放…`)
    }
    return ok
  } catch (e: any) {
    console.error(e)
    if (!silent) message.error(`${id} 启动失败：` + (e?.message || e))
    return false
  }
}

// 更激进：只要清单存在即认为成功（不必等待首段）
async function waitUntilReady(id: string, timeoutMs = 10000, pollMs = 800) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const resp = await fetch(`/api/streams/${id}/status`)
      if (resp.ok) {
        const s = await resp.json()
        const probe = s?.probe
        if (probe && probe.m3u8Exists) return true
      }
    } catch (_) {}
    await new Promise(r => setTimeout(r, pollMs))
  }
  return false
}

onMounted(() => { (async () => { await discoverPorts(); await detect() })() })

// 修改连接参数或检测设置时，自动重新检测（500ms 防抖）
let t: any = null
watch([nvrUser, nvrPass, nvrHost, detectMain, detectSub, portCount], () => {
  if (t) clearTimeout(t)
  t = setTimeout(async () => { await discoverPorts(); await detect() }, 500)
})

// 自动检测并启动：优先子码流，必要时可尝试主码流
async function detect() {
  loading.value = true
  try {
    const maxPort = Math.max(8, portCount.value || 0)
    // 初始化 8 宫格占位，显示检测中
    const init: CamEntry[] = []
    for (let i = 1; i <= maxPort; i++) {
      init.push({ port: i, selected: '02', hasSub: false, hasMain: false, idSub: `cam${i}02`, idMain: `cam${i}01`, url: undefined, status: 'detecting' })
    }
    cams.value = init

    // 并行检测每个端口
    await Promise.all(init.map(async (c, idx) => {
      try {
        let okSub = false
        let okMain = false
        if (detectSub.value) okSub = await startOne(c.idSub, true, 4000, 250)
        if (!okSub && detectMain.value) okMain = await startOne(c.idMain, true, 4000, 250)
        c.hasSub = okSub
        c.hasMain = okMain
        if (okSub || okMain) {
          c.selected = okSub ? '02' : '01'
          const id = c.selected === '02' ? c.idSub : c.idMain
          c.url = urls.value[id]
          c.status = 'ok'
        } else {
          c.status = 'none'
          c.url = undefined
        }
        // 触发响应式更新
        cams.value[idx] = { ...c }
      } catch (e: any) {
        c.status = 'error'; c.err = e?.message || String(e); cams.value[idx] = { ...c }
      }
    }))
  } finally {
    loading.value = false
  }
}

// 调用后端 ISAPI 代理自动发现端口数（若失败不阻断）
async function discoverPorts() {
  try {
    const params = new URLSearchParams({ host: nvrHost.value, user: nvrUser.value, pass: nvrPass.value, scheme: nvrScheme.value })
    if (nvrHttpPort.value) params.set('httpPort', String(nvrHttpPort.value))
    const resp = await fetch(`/api/nvr/channels?${params.toString()}`)
    if (!resp.ok) return
    const data: any = await resp.json()
    const pc = Number(data?.portCount || 0)
    if (pc && pc > 0) {
      portCount.value = Math.max(8, pc)
    }
  } catch (_) {
    // ignore
  }
}
async function changeStream(c: CamEntry, val: '01'|'02') {
  c.selected = val
  const id = val === '02' ? c.idSub : c.idMain
  const ok = await startOne(id, false)
  if (ok) {
    c.url = urls.value[id]
    if (val === '01') c.hasMain = true
    if (val === '02') c.hasSub = true
  }
}
</script>

<template>
  <a-layout style="min-height:calc(100vh - 64px);">
    <a-layout-content style="padding:12px; color:#000;">

      <div class="multi-panel">
        <div class="grid-3">
          <div class="cell" :class="{ 'underline-bottom': c.port === 6, 'cell-no-cam': c.status==='detecting' || c.status==='error', 'cell-none': c.status==='none' }" v-for="c in cams" :key="c.port">
            <div class="cell-header">
              <span>摄像头 {{ c.port }}</span>
              <a-select size="small" :value="c.selected" style="width:120px" :disabled="c.status!=='ok'" @change="(v:any)=>changeStream(c, v)">
                <a-select-option value="02">子码流 (02)</a-select-option>
                <a-select-option value="01">主码流 (01)</a-select-option>
              </a-select>
            </div>
            <div class="cell-body">
              <template v-if="c.status==='ok'">
                <VideoPlayer v-if="c.url" :src="c.url" />
                <div v-else class="placeholder inverse">等待清单…</div>
              </template>
              <template v-else-if="c.status==='detecting'">
                <div class="placeholder">正在自动检测摄像头…</div>
              </template>
              <template v-else-if="c.status==='none'">
                <div class="placeholder">无摄像头</div>
              </template>
              <template v-else>
                <div class="placeholder">检测失败</div>
              </template>
            </div>
          </div>
        </div>
      </div>
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
/* 悬浮大框 */
.multi-panel {
  margin-top: 0;
  background: #ffffff;
  border: 1px solid #dddddd;
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0,0,0,0.06);
  height: calc(100vh - 64px - 24px);
  overflow: hidden; /* 圆角裁剪，和左侧悬浮面板一致 */
}

/* 宫格（每行3列） */
.grid-3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  height: 100%;
  grid-auto-rows: 1fr;
}

/* 单元格内细分割线 */
.cell {
  display: flex;
  flex-direction: column;
  padding: 0;
  border-right: 1px solid #eeeeee;
  border-bottom: 1px solid #eeeeee;
}
.cell:nth-child(3n) { border-right: none; }
.grid-3 > .cell:nth-last-child(-n + 3) { border-bottom: none; }

.cell-header {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #000;
  font-weight: 600;
  padding: 6px;
}
.cell-body { flex: 1; display: flex; align-items: stretch; justify-content: stretch; background:#000; }
.placeholder { flex: 1; display: flex; align-items: center; justify-content: center; color: #00000099; background: transparent; width: 100%; }
.placeholder.inverse { color: #ffffff99; }
.cell-body :deep(video) { width: 100% !important; height: 100% !important; max-width: 100% !important; object-fit: contain; display:block; background:#000; }
.muted { color: #00000099; }
.error { color: #d92c2c; }
/* 摄像头6下方加底线（高亮色） */
.underline-bottom { border-bottom: 1px solid #eeeeee !important; }

/* 检测中/错误/无摄像头 使用白底 */
.cell-no-cam { background: #fff; }
.cell-no-cam .cell-body { background: #fff !important; }
.cell-none { background: #fff; }
.cell-none .cell-body { background: #fff !important; }
</style>
