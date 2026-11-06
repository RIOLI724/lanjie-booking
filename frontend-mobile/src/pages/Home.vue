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
      <router-link to="/book" class="card home-card">
        <b>订场</b>
        <span class="go">›</span>
      </router-link>
      <router-link to="/coach" class="card home-card">
        <b>约教练</b>
        <span class="go">›</span>
      </router-link>
      <router-link to="/pickup" class="card home-card">
        <b>拼场</b>
        <span class="go">›</span>
      </router-link>
      <router-link to="/ai" class="card home-card">
        <b>AI 前台</b>
        <span class="go">›</span>
      </router-link>
    </div>

    <DockBar />
  </div>
</template>
