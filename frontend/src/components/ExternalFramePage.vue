<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  envVar: string
  fallbackUrl: string
  theme?: 'light' | 'panel'
}>(), {
  theme: 'panel'
})

const envVars = import.meta.env as Record<string, string | undefined>
const url = computed(() => envVars[props.envVar] || props.fallbackUrl)
const cardClass = computed(() => props.theme === 'light' ? 'frame-card light' : 'frame-card panel')
</script>

<template>
  <a-layout class="frame-layout">
    <a-layout-content class="frame-content">
      <div :class="cardClass">
        <iframe :src="url" frameborder="0" class="frame" />
      </div>
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
.frame-layout {
  min-height: calc(100vh - 64px);
}

.frame-content {
  padding: 12px;
  color: var(--text-color);
}

.frame-card {
  background: var(--panel-bg);
  border: 1px solid var(--panel-border);
  border-radius: 8px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.18);
  height: calc(100vh - 64px - 24px);
  overflow: hidden;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.frame-card.light {
  background: #ffffff;
  border-color: #dddddd;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
}

.frame-card.panel:hover,
.frame-card.light:hover {
  border-color: rgba(27, 146, 253, 0.45);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.18);
}

.frame {
  width: 100%;
  height: 100%;
  display: block;
}
</style>
