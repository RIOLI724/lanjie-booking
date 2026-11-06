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

    <p class="section-label">W1 里程碑：注册登录已通，业务模块按排期逐周点亮</p>
    <div class="grid">
      <div class="card disabled">
        <b>订场</b>
        <small>4 片全场 · 1 小时格子</small>
        <span class="tag">W2 开通</span>
      </div>
      <div class="card disabled">
        <b>约教练</b>
        <small>私教 / 团课 / 课包</small>
        <span class="tag">W5 开通</span>
      </div>
      <div class="card disabled">
        <b>拼场</b>
        <small>3v3 / 4v4 / 5v5</small>
        <span class="tag">W7 开通</span>
      </div>
      <div class="card disabled">
        <b>AI 前台</b>
        <small>查空场 · 锁位 · 答规则</small>
        <span class="tag">W9 上线</span>
      </div>
    </div>

    <DockBar />
  </div>
</template>
