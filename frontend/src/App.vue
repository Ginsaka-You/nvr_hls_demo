<script setup lang="ts">
import { ref } from 'vue'
import { SettingFilled } from '@ant-design/icons-vue'
import Overview from './dashboard/Overview.vue'
import MultiCam from './pages/MultiCam.vue'
import Settings from './pages/Settings.vue'
import ImsiTrend from './pages/ImsiTrend.vue'
import RadarPlot from './pages/RadarPlot.vue'
import SeismicWave from './pages/SeismicWave.vue'
import DroneTelemetry from './pages/DroneTelemetry.vue'
import AlertPanel from './components/AlertPanel.vue'
import { connectAlerts } from './store/alerts'

type Tab = 'main'|'multicam'|'imsi'|'radar'|'seismic'|'drone'|'settings'
const tab = ref<Tab>('main')

function onMenuClick(e: any) {
  const key = e?.key as Tab
  if (key) tab.value = key
}

function gotoSettings() { tab.value = 'settings' }
function gotoHome() { tab.value = 'main' }

// 全局建立告警订阅（推送/拉取），保持在所有页面可用
connectAlerts()
</script>

<template>
  <a-layout style="min-height:100vh;">
    <a-layout-header style="display:flex; align-items:center; gap:12px; background:#ffffff; border-bottom:1px solid #dddddd; box-shadow:0 2px 8px rgba(0,0,0,0.06);">
      <div @click="gotoHome" style="width:32px; height:32px; background:#c9924d; border-radius:4px; display:flex; align-items:center; justify-content:center; color:#000; font-weight:700; cursor:pointer;">L</div>
      <div @click="gotoHome" style="font-weight:600; color:#000; cursor:pointer; line-height:32px;">
        天玺金盾
      </div>
      <div style="margin-left:auto; display:flex; align-items:center; gap:12px; height:32px;">
        <a-tooltip title="设置">
          <a-button type="text" @click="gotoSettings" style="width:32px;height:32px;display:flex;align-items:center;justify-content:center;font-size:18px;line-height:1;color:var(--accent-color);">
            <SettingFilled />
          </a-button>
        </a-tooltip>
        <a-avatar :size="32" style="background:#c9924d; color:#000; display:flex; align-items:center; justify-content:center;">U</a-avatar>
      </div>
    </a-layout-header>
    <a-layout>
    <a-layout-sider width="220" :style="{background:'var(--bg-color)'}">
      <div style="margin:12px; background:#ffffff; border:1px solid #dddddd; border-radius:8px; box-shadow:0 2px 10px rgba(0,0,0,0.06); overflow:hidden; height:calc(100vh - 64px - 24px); display:flex; flex-direction:column;">
        <a-menu class="no-scrollbar" theme="light" mode="inline" :selectedKeys="[tab]" @click="onMenuClick" :style="{borderRight:0, background:'#fff', height:'100%', overflow:'hidden'}">
        <a-menu-item key="main">主屏幕</a-menu-item>
        <a-menu-item key="multicam">多摄像头</a-menu-item>
        <a-menu-item key="imsi">IMSI 趋势</a-menu-item>
          <a-menu-item key="radar">雷达速度-距离图</a-menu-item>
          <a-menu-item key="seismic">震动波形</a-menu-item>
          <a-menu-item key="drone">无人机遥测</a-menu-item>
        </a-menu>
      </div>
    </a-layout-sider>
    <a-layout>
      <a-layout-content>
        <Overview v-if="tab==='main'" />
        <MultiCam v-else-if="tab==='multicam'" />
        <ImsiTrend v-else-if="tab==='imsi'" />
        <RadarPlot v-else-if="tab==='radar'" />
        <SeismicWave v-else-if="tab==='seismic'" />
        <DroneTelemetry v-else-if="tab==='drone'" />
        <Settings v-else-if="tab==='settings'" />
      </a-layout-content>
      <!-- 右侧告警面板（仅主屏幕显示） -->
      <a-layout-sider v-if="tab==='main'" width="380" :style="{background:'var(--bg-color)'}">
        <div style="margin:12px; background:#ffffff; border:1px solid #dddddd; border-radius:8px; box-shadow:0 2px 10px rgba(0,0,0,0.06); padding:8px; height:calc(100vh - 64px - 24px); display:flex; flex-direction:column; overflow:hidden;">
          <div style="font-weight:600; margin:4px 0 8px;">告警队列</div>
          <AlertPanel />
        </div>
      </a-layout-sider>
    </a-layout>
    </a-layout>
  </a-layout>
  
</template>

<style scoped>
</style>
