<script setup lang="ts">
const props = withDefaults(defineProps<{
  message: string
}>(), {
  message: '功能开发中'
})
</script>

<template>
  <a-layout class="placeholder-layout">
    <a-layout-content class="placeholder-content">
      <a-card size="small" class="placeholder-card">
        <div class="placeholder-body">{{ props.message }}</div>
      </a-card>
    </a-layout-content>
  </a-layout>
</template>

<style scoped>
.placeholder-layout {
  min-height: calc(100vh - 64px);
  background: var(--bg-color);
  color: #000000;
}

.placeholder-content {
  padding: 12px;
}

.placeholder-card {
  background: #ffffff;
  border-color: #dddddd;
}

.placeholder-body {
  height: 480px;
  color: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
