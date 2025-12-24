<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import type { Component } from 'vue'
import { SettingFilled } from '@ant-design/icons-vue'
import Overview from './dashboard/Overview.vue'
import MultiCam from './pages/MultiCam.vue'
import Settings from './pages/Settings.vue'
import ImsiTrend from './pages/ImsiTrend.vue'
import RadarPlot from './pages/RadarPlot.vue'
import EventCenter from './pages/EventCenter.vue'
import RiskModel from './pages/RiskModel.vue'
import PlaceholderPage from './components/PlaceholderPage.vue'
import { ensureSettingsLoaded } from './store/config'
import { connectAlerts } from './store/alerts'
import { connectRadar } from './store/radar'
import { connectDeviceMonitoring } from './store/devices'
import { connectImsiUpdates } from './store/imsiUpdates'

type Tab = 'main'|'multicam'|'events'|'risk'|'imsi'|'radar'|'seismic'|'drone'|'settings'
type TabGroup = 'primary'|'hidden'

interface TabDefinition {
  key: Tab
  label: string
  component?: Component
  props?: Record<string, unknown>
  group: TabGroup
  standalone?: boolean
}

const tab = ref<Tab>('main')

const tabDefinitions: TabDefinition[] = [
  { key: 'main', label: '主屏幕', component: Overview, group: 'primary' },
  { key: 'multicam', label: '多摄像头', component: MultiCam, group: 'primary', standalone: true },
  { key: 'events', label: '事件中心', component: EventCenter, group: 'primary' },
  { key: 'risk', label: '风控模型', component: RiskModel, group: 'primary' },
  { key: 'imsi', label: 'IMSI 趋势', component: ImsiTrend, group: 'primary' },
  { key: 'radar', label: '相控阵雷达', component: RadarPlot, group: 'primary' },
  { key: 'seismic', label: '震动波形', component: PlaceholderPage, props: { message: '震动波形图表占位' }, group: 'primary' },
  { key: 'drone', label: '无人机遥测', component: PlaceholderPage, props: { message: '无人机遥测面板占位' }, group: 'primary' },
  { key: 'settings', label: '设置', component: Settings, group: 'hidden' }
]

const tabMap = tabDefinitions.reduce<Record<Tab, TabDefinition>>((acc, def) => {
  acc[def.key] = def
  return acc
}, {} as Record<Tab, TabDefinition>)

const primaryTabs = computed(() => tabDefinitions.filter(def => def.group === 'primary'))
const navItems = computed(() => primaryTabs.value.map(def => ({ key: def.key, label: def.label })))
const splitIdx = computed(() => Math.ceil(navItems.value.length / 2))
const navLeft = computed(() => navItems.value.slice(0, splitIdx.value))
const navRight = computed(() => navItems.value.slice(splitIdx.value))

const isCompact = ref(false)
function handleResize() {
  isCompact.value = window.innerWidth < 960
}
onMounted(() => {
  handleResize()
  window.addEventListener('resize', handleResize)
})
onUnmounted(() => window.removeEventListener('resize', handleResize))

onMounted(async () => {
  try {
    await ensureSettingsLoaded()
  } catch (err) {
    console.warn('Settings load failed during app init:', err)
  } finally {
    connectAlerts()
    connectRadar()
    connectDeviceMonitoring()
    connectImsiUpdates()
  }
})

function onMenuClick(e: any) {
  const key = e?.key as Tab | undefined
  if (key && tabMap[key]) {
    tab.value = key
  }
}

function gotoSettings() { tab.value = 'settings' }
function gotoHome() { tab.value = 'main' }

const activeDefinition = computed(() => tabMap[tab.value])
const activeComponent = computed<Component | null>(() => {
  const def = activeDefinition.value
  if (!def || def.standalone) return null
  return def.component ?? null
})
const activeProps = computed(() => activeDefinition.value?.props ?? {})
</script>

<template>
  <a-layout style="min-height:100vh;">
    <a-layout-header class="top-header">
      <div class="top-bar">
        <div class="nav-section nav-left">
          <a-menu class="top-menu" mode="horizontal" theme="dark" :selectedKeys="[tab]" @click="onMenuClick">
            <a-menu-item v-for="it in navLeft" :key="it.key">{{ it.label }}</a-menu-item>
          </a-menu>
        </div>
        <div class="brand" @click="gotoHome">
          天玺金盾
          <span class="brand-underline"></span>
        </div>
        <div class="nav-section nav-right">
          <template v-if="!isCompact">
            <a-menu class="top-menu top-menu-right" mode="horizontal" theme="dark" :selectedKeys="[tab]" @click="onMenuClick">
              <a-menu-item v-for="it in navRight" :key="it.key">{{ it.label }}</a-menu-item>
            </a-menu>
          </template>
          <template v-else>
            <a-dropdown class="compact-trigger">
              <a-button type="text">…</a-button>
              <template #overlay>
                <a-menu :selectedKeys="[tab]" theme="dark" @click="onMenuClick">
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
          </div>
        </div>
      </div>
    </a-layout-header>
    <a-layout>
      <a-layout-content>
        <MultiCam v-if="tab==='multicam'" class="tab-view" />
        <component
          v-if="activeComponent"
          :key="tab"
          :is="activeComponent"
          class="tab-view"
          v-bind="activeProps"
        />
      </a-layout-content>
    </a-layout>
  </a-layout>
  
</template>

<style scoped>
.top-header {
  position: sticky;
  top: 0;
  z-index: 20;
  background: linear-gradient(90deg, rgba(4, 9, 18, 0.95), rgba(10, 28, 48, 0.85), rgba(4, 9, 18, 0.95));
  border-bottom: 1px solid rgba(0, 229, 255, 0.35);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.4);
  padding: 0 16px;
  overflow: hidden;
}

.top-header::before,
.top-header::after {
  content: "";
  position: absolute;
  bottom: 0;
  width: 140px;
  height: 12px;
  border-top: 2px solid rgba(0, 229, 255, 0.7);
  border-left: 2px solid rgba(0, 229, 255, 0.7);
  border-right: 2px solid rgba(0, 229, 255, 0.7);
}

.top-header::before {
  left: 18px;
  transform: skewX(-20deg);
}

.top-header::after {
  right: 18px;
  transform: skewX(20deg);
}



.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 64px;
  position: relative;
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

.top-menu { border-bottom: 0; background: transparent; }
.compact-trigger { display: none; }
.top-menu :deep(.ant-menu-item) {
  padding-inline: 12px;
  white-space: nowrap;
  color: #94a3b8;
}

.top-menu :deep(.ant-menu-item-selected) {
  color: #00e5ff;
  background: transparent !important;
}

.top-menu :deep(.ant-menu-item:hover) {
  color: #7cf6ff;
}

.brand {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  font-weight: 700;
  font-size: 32px;
  color: #00e5ff;
  cursor: pointer;
  line-height: 32px;
  white-space: nowrap;
  letter-spacing: 8px;
  text-shadow: 0 0 12px rgba(0, 229, 255, 0.65);
  display: flex;
  flex-direction: column;
  align-items: center;
}

.brand-underline {
  margin-top: 6px;
  width: 200px;
  height: 2px;
  background: linear-gradient(90deg, rgba(0, 229, 255, 0), rgba(0, 229, 255, 0.9), rgba(0, 229, 255, 0));
  box-shadow: 0 0 10px rgba(0, 229, 255, 0.8);
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
    font-size: 22px;
  }

  .brand-underline {
    width: 140px;
  }

  .compact-trigger { display: inline-flex; }

  .header-actions {
    gap: 8px;
  }
}
</style>
