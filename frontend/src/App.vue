<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { SettingFilled } from '@ant-design/icons-vue'
import Overview from './dashboard/Overview.vue'
import MultiCam from './pages/MultiCam.vue'
import Settings from './pages/Settings.vue'
import ImsiTrend from './pages/ImsiTrend.vue'
import RadarPlot from './pages/RadarPlot.vue'
import SeismicWave from './pages/SeismicWave.vue'
import DroneTelemetry from './pages/DroneTelemetry.vue'
import BigDataView8 from './pages/BigDataView8.vue'
import BigDataView61 from './pages/BigDataView61.vue'
import BigDataView67 from './pages/BigDataView67.vue'
import AxureDemo from './pages/AxureDemo.vue'
import AlertPanel from './components/AlertPanel.vue'
import { connectAlerts } from './store/alerts'
import { connectRadar } from './store/radar'
import { connectDeviceMonitoring } from './store/devices'

type Tab = 'main'|'multicam'|'imsi'|'radar'|'seismic'|'drone'|'big8'|'big61'|'big67'|'axure'|'settings'
const tab = ref<Tab>('main')

// 顶部导航项（原左侧栏）：仅核心功能页，外链与大屏放到头像右侧下拉
const navItems = [
  { key: 'main', label: '主屏幕' },
  { key: 'multicam', label: '多摄像头' },
  { key: 'imsi', label: 'IMSI 趋势' },
  { key: 'radar', label: '相控阵雷达' },
  { key: 'seismic', label: '震动波形' },
  { key: 'drone', label: '无人机遥测' },
] as { key: Tab, label: string }[]
// 头像右侧下拉项
const moreItems = [
  { key: 'big8', label: '兰州智慧消防大数据平台' },
  { key: 'big61', label: '智慧小区大数据分析' },
  { key: 'big67', label: '智慧旅游综合服务平台' },
  { key: 'axure', label: 'Axure 原型' },
] as { key: Tab, label: string }[]
const splitIdx = Math.ceil(navItems.length / 2)
const navLeft = computed(() => navItems.slice(0, splitIdx))
const navRight = computed(() => navItems.slice(splitIdx))

const isCompact = ref(false)
function handleResize() {
  isCompact.value = window.innerWidth < 960
}
onMounted(() => {
  handleResize()
  window.addEventListener('resize', handleResize)
})
onUnmounted(() => window.removeEventListener('resize', handleResize))

function onMenuClick(e: any) {
  const key = e?.key as Tab
  if (key) tab.value = key
}

function gotoSettings() { tab.value = 'settings' }
function gotoHome() { tab.value = 'main' }

// 全局建立告警订阅（推送/拉取），保持在所有页面可用
connectAlerts()
connectRadar()
connectDeviceMonitoring()
</script>

<template>
  <a-layout style="min-height:100vh;">
    <a-layout-header class="top-header">
      <div class="top-bar">
        <div class="nav-section nav-left">
          <a-menu class="top-menu" mode="horizontal" theme="light" :selectedKeys="[tab]" @click="onMenuClick">
            <a-menu-item v-for="it in navLeft" :key="it.key">{{ it.label }}</a-menu-item>
          </a-menu>
        </div>
        <div class="brand" @click="gotoHome">天玺金盾</div>
        <div class="nav-section nav-right">
          <template v-if="!isCompact">
            <a-menu class="top-menu top-menu-right" mode="horizontal" theme="light" :selectedKeys="[tab]" @click="onMenuClick">
              <a-menu-item v-for="it in navRight" :key="it.key">{{ it.label }}</a-menu-item>
            </a-menu>
          </template>
          <template v-else>
            <a-dropdown class="compact-trigger">
              <a-button type="text">…</a-button>
              <template #overlay>
                <a-menu :selectedKeys="[tab]" @click="onMenuClick">
                  <a-menu-item v-for="it in navRight" :key="'compact-'+it.key">{{ it.label }}</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </template>
          <div class="header-actions">
            <a-tooltip title="设置">
              <a-button type="text" @click="gotoSettings" class="settings-btn">
                <SettingFilled />
              </a-button>
            </a-tooltip>
            <a-avatar :size="32" class="user-avatar">U</a-avatar>
            <a-dropdown placement="bottomRight">
              <a-button>更多大屏</a-button>
              <template #overlay>
                <a-menu :selectedKeys="[]" @click="onMenuClick">
                  <a-menu-item v-for="it in moreItems" :key="it.key">{{ it.label }}</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
        </div>
      </div>
    </a-layout-header>
    <a-layout>
      <a-layout-content>
        <Overview v-if="tab==='main'" />
        <MultiCam v-else-if="tab==='multicam'" />
        <ImsiTrend v-else-if="tab==='imsi'" />
        <RadarPlot v-else-if="tab==='radar'" />
        <SeismicWave v-else-if="tab==='seismic'" />
        <DroneTelemetry v-else-if="tab==='drone'" />
        <BigDataView8 v-else-if="tab==='big8'" />
        <BigDataView61 v-else-if="tab==='big61'" />
        <BigDataView67 v-else-if="tab==='big67'" />
        <AxureDemo v-else-if="tab==='axure'" />
        <Settings v-else-if="tab==='settings'" />
      </a-layout-content>
    </a-layout>
  </a-layout>
  
</template>

<style scoped>
.top-header {
  position: sticky;
  top: 0;
  z-index: 10;
  background: var(--bg-color);
  border-bottom: 1px solid rgba(27, 146, 253, 0.35);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.25);
  padding: 0 12px;
}



.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 64px;
  flex-wrap: nowrap;
}

.nav-section {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.nav-left {
  justify-content: flex-start;
}

.nav-right {
  justify-content: flex-end;
}

.top-menu { border-bottom: 0; }
.compact-trigger { display: none; }
.top-menu :deep(.ant-menu-item) {
  padding-inline: 12px;
  white-space: nowrap;
}

.brand {
  flex: 0 0 auto;
  font-weight: 600;
  color: var(--text-color);
  cursor: pointer;
  line-height: 32px;
  white-space: nowrap;
  max-width: 35%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.settings-btn {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  line-height: 1;
  color: var(--accent-color);
}

.user-avatar {
  background: var(--accent-color);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

@media (max-width: 960px) {
  .top-bar {
    gap: 8px;
    padding: 6px 0;
  }

  .brand {
    font-size: 15px;
    max-width: 50%;
  }

  .compact-trigger { display: inline-flex; }

  .header-actions {
    gap: 8px;
  }
}
</style>
