<script setup lang="ts">
import { onMounted } from 'vue'
import DockBar from '@/components/DockBar.vue'
import { loadMe, sessionState } from '@/stores/session'

onMounted(() => {
  if (!sessionState.loaded) {
    loadMe().catch(() => {})
  }
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">
      你好，{{ sessionState.me?.nickname ?? '球友' }}<br />
      今晚，去哪片场？
    </h1>

    <div class="grid">
      <div class="card disabled">
        <b>订场</b>
      </div>
      <div class="card disabled">
        <b>约教练</b>
      </div>
      <div class="card disabled">
        <b>拼场</b>
      </div>
      <div class="card disabled">
        <b>AI 前台</b>
      </div>
    </div>

    <DockBar />
  </div>
</template>
