<script setup lang="ts">
import { ref } from 'vue'
import HlsDemo from './HlsDemo.vue'
import Overview from './dashboard/Overview.vue'
import ImsiTrend from './pages/ImsiTrend.vue'
import RadarPlot from './pages/RadarPlot.vue'
import SeismicWave from './pages/SeismicWave.vue'
import DroneTelemetry from './pages/DroneTelemetry.vue'

type Tab = 'main'|'camera'|'imsi'|'radar'|'seismic'|'drone'
const tab = ref<Tab>('main')

function onMenuClick(e: any) {
  const key = e?.key as Tab
  if (key) tab.value = key
}
</script>

<template>
  <a-layout style="min-height:100vh;">
    <a-layout-header style="background:#0f172a; display:flex; align-items:center; gap:12px;">
      <div style="width:24px; height:24px; background:#60a5fa; border-radius:4px; display:flex; align-items:center; justify-content:center; color:#0b1220; font-weight:700;">L</div>
      <div style="font-weight:600; color:#93c5fd;">某某科技</div>
      <div style="margin-left:auto; display:flex; align-items:center; gap:12px;">
        <a-avatar style="background:#334155">U</a-avatar>
      </div>
    </a-layout-header>
    <a-layout>
    <a-layout-sider width="192" style="background:#0f172a; border-right:1px solid #1e293b;">
      <a-menu theme="dark" mode="inline" :selectedKeys="[tab]" @click="onMenuClick" style="background:#0f172a;">
        <a-menu-item key="main">主屏幕</a-menu-item>
        <a-menu-item key="camera">摄像头</a-menu-item>
        <a-menu-item key="imsi">IMSI 趋势</a-menu-item>
        <a-menu-item key="radar">雷达速度-距离图</a-menu-item>
        <a-menu-item key="seismic">震动波形</a-menu-item>
        <a-menu-item key="drone">无人机遥测</a-menu-item>
      </a-menu>
    </a-layout-sider>
    <a-layout-content style="background:#0b1220;">
      <Overview v-if="tab==='main'" />
      <HlsDemo v-else-if="tab==='camera'" />
      <ImsiTrend v-else-if="tab==='imsi'" />
      <RadarPlot v-else-if="tab==='radar'" />
      <SeismicWave v-else-if="tab==='seismic'" />
      <DroneTelemetry v-else-if="tab==='drone'" />
    </a-layout-content>
    </a-layout>
  </a-layout>
  
</template>

<style scoped>
</style>
