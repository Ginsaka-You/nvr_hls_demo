<script setup lang="ts">
import { onMounted, ref } from 'vue'

const echartsAvailable = ref(false)

onMounted(() => {
  const echarts = (window as any)?.echarts
  if (!echarts) { echartsAvailable.value = false; return }
  echartsAvailable.value = true
  draw(echarts, 'radar', '雷达速度/距离')
  draw(echarts, 'seismic', '震动波形')
  draw(echarts, 'imsi', 'IMSI 活跃趋势')
})

function draw(echarts: any, id: string, name: string) {
  const dom = document.getElementById('chart_'+id)
  if (!dom) return
  const inst = echarts.init(dom)
  inst.setOption({
    backgroundColor: 'transparent',
    textStyle: { color: '#000000' },
    title: { text: name, left: 6, top: 4, textStyle: { color: '#000000', fontSize: 12 } },
    xAxis: { type: 'category', show: false },
    yAxis: { type: 'value', show: false },
    grid: { left: 8, right: 8, top: 24, bottom: 8 },
    series: [{ type: 'line', data: Array.from({length: 24}).map(()=>Math.random()*10), smooth: true, areaStyle: {} }]
  })
}
</script>

<template>
  <div style="display:grid;grid-template-columns:1fr;gap:8px">
    <div v-if="!echartsAvailable" style="border:1px solid #dddddd;border-radius:6px;padding:8px;color:#000000;background:#ffffff">
      传感器面板占位（未安装 echarts）。将显示：IMSI 新现/趋势、雷达速度-距离、震动波形。
    </div>
    <div v-else style="display:grid;grid-template-columns:1fr;gap:8px">
      <div id="chart_radar" style="height:140px;border:1px solid #dddddd;border-radius:6px;background:#ffffff"></div>
      <div id="chart_seismic" style="height:140px;border:1px solid #dddddd;border-radius:6px;background:#ffffff"></div>
      <div id="chart_imsi" style="height:140px;border:1px solid #dddddd;border-radius:6px;background:#ffffff"></div>
    </div>
  </div>
</template>

<style scoped>
</style>
