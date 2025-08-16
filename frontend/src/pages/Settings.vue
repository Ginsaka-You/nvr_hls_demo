<script setup lang="ts">
import { ref } from 'vue'
import { nvrHost, nvrUser, nvrPass, nvrScheme, nvrHttpPort, portCount, detectMain, detectSub } from '@/store/config'
import { message, Modal } from 'ant-design-vue'

const sec = ref<'multicam'|'imsi'|'radar'|'seismic'|'drone'>('multicam')

function saveAndNotify() {
  message.success('设置已保存，相关页面将自动生效')
}

// 清除缓存（删除 HLS 清单与分片）
const clearTargetId = ref<string>('')
async function clearHls() {
  const id = (clearTargetId.value || '').trim()
  const title = id ? `确定删除 ${id} 的 HLS 文件？` : '确定删除所有流的 HLS 文件？'
  Modal.confirm({
    title,
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        const body = id ? `id=${encodeURIComponent(id)}` : ''
        const resp = await fetch('/api/hls/clear', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body
        })
        if (!resp.ok) throw new Error(`API ${resp.status}`)
        const data: any = await resp.json()
        if (data?.ok) {
          message.success(`已删除 ${data.deleted || 0} 个文件`)
        } else {
          message.error('删除失败：' + (data?.error || '未知错误'))
        }
      } catch (e: any) {
        message.error('删除失败：' + (e?.message || e))
      }
    }
  })
}
</script>

<template>
  <a-layout style="min-height:calc(100vh - 64px);">
    <a-layout-content style="padding:16px; color:#000;">
      <a-typography-title :level="4" style="color:#000">系统设置</a-typography-title>

      <div style="display:grid; grid-template-columns: 240px 1fr; gap: 12px; align-items:start;">
        <!-- 左侧悬浮设置分类 -->
        <div style="background:#fff; border:1px solid #ddd; border-radius:8px; box-shadow:0 2px 10px rgba(0,0,0,0.06); overflow:hidden;">
          <a-menu mode="inline" theme="light" :selectedKeys="[sec]" :style="{borderRight:0}">
            <a-menu-item key="multicam" @click="sec='multicam'">多摄像头</a-menu-item>
            <a-menu-item key="imsi" @click="sec='imsi'">IMSI</a-menu-item>
            <a-menu-item key="radar" @click="sec='radar'">雷达</a-menu-item>
            <a-menu-item key="seismic" @click="sec='seismic'">震动</a-menu-item>
            <a-menu-item key="drone" @click="sec='drone'">无人机</a-menu-item>
          </a-menu>
        </div>

        <!-- 右侧设置面板 -->
        <div style="background:#fff; border:1px solid #ddd; border-radius:8px; box-shadow:0 2px 10px rgba(0,0,0,0.06); padding:12px;">
          <template v-if="sec==='multicam'">
            <a-typography-title :level="5" style="color:#000">多摄像头 · 录像机与检测</a-typography-title>
            <a-form layout="horizontal" :label-col="{ span: 5 }" :wrapper-col="{ span: 16 }">
              <a-form-item label="协议">
                <a-radio-group v-model:value="nvrScheme">
                  <a-radio value="http">HTTP</a-radio>
                  <a-radio value="https">HTTPS</a-radio>
                </a-radio-group>
              </a-form-item>
              <a-form-item label="NVR Host"><a-input v-model:value="nvrHost" style="width:220px" /></a-form-item>
              <a-form-item label="用户名"><a-input v-model:value="nvrUser" style="width:220px" /></a-form-item>
              <a-form-item label="密码"><a-input-password v-model:value="nvrPass" style="width:220px" /></a-form-item>
              <a-form-item label="端口"><a-input-number v-model:value="nvrHttpPort" :min="1" :max="65535" style="width:220px" placeholder="留空=默认(80/443)" /></a-form-item>
              <a-form-item label="端口数"><a-input-number v-model:value="portCount" :min="1" :max="32" style="width:220px" /></a-form-item>
              <a-form-item label="检测类型">
                <a-checkbox v-model:checked="detectSub">子(02)</a-checkbox>
                <a-checkbox v-model:checked="detectMain" style="margin-left:12px">主(01)</a-checkbox>
              </a-form-item>
              <a-form-item :wrapper-col="{ offset: 5 }">
                <a-button type="primary" @click="saveAndNotify">保存</a-button>
              </a-form-item>
            </a-form>
            <a-alert type="info" show-icon message="说明：保存后，多摄像头页面会按新参数自动检测并播放。" />
          </template>

          <template v-else>
            <div style="color:#00000099;">该模块暂未提供可配置项。</div>
          </template>

          <a-divider />
          <a-typography-title :level="5" style="color:#000">缓存与临时文件</a-typography-title>
          <a-form layout="horizontal" :label-col="{ span: 5 }" :wrapper-col="{ span: 16 }">
            <a-form-item label="Stream ID">
              <a-input v-model:value="clearTargetId" style="width:220px" placeholder="留空表示全部，如 cam402" />
            </a-form-item>
            <a-form-item :wrapper-col="{ offset: 5 }">
              <a-button danger @click="clearHls">清除缓存文件（删除 .m3u8 与 .ts）</a-button>
            </a-form-item>
          </a-form>
        </div>
      </div>
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
</style>
