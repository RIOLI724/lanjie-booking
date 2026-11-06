<script setup lang="ts">
import { onMounted } from 'vue'
import DockBar from '@/components/DockBar.vue'
import CourtBackdrop from '@/components/CourtBackdrop.vue'
import { loadMe, sessionState } from '@/stores/session'

onMounted(() => {
  if (!sessionState.loaded) {
    loadMe().catch(() => {})
  }
})
</script>

<template>
  <div class="page" style="padding-top: 0">
    <div class="home-hero">
      <CourtBackdrop />
      <div class="home-hero-shade"></div>
      <div class="home-hero-copy">
        <small>城东体育馆 · 4 片全场</small>
        <h1>你好，{{ sessionState.me?.nickname ?? '球友' }}<br />今晚，去哪片场？</h1>
      </div>
    </div>

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
