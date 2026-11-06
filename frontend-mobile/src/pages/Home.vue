<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DockBar from '@/components/DockBar.vue'
import CourtBackdrop from '@/components/CourtBackdrop.vue'
import { loadMe, sessionState } from '@/stores/session'
import { fetchSlots } from '@/api/venue'

const sheetOpen = ref(true)
const availableCount = ref(0)
const today = new Date()
const dateLabel = `${today.getMonth() + 1}/${String(today.getDate()).padStart(2, '0')}`
const localIso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

onMounted(async () => {
  if (!sessionState.loaded) {
    loadMe().catch(() => {})
  }
  try {
    const slots = await fetchSlots(localIso)
    availableCount.value = slots.filter((s) => {
      if (s.status !== 'AVAILABLE') return false
      return new Date(`${localIso}T${s.startAt}:00`).getTime() > Date.now()
    }).length
  } catch {
    availableCount.value = 0
  }
})
</script>

<template>
  <div class="page home-demo">
    <CourtBackdrop />
    <div class="home-demo-shade"></div>

    <header class="home-top">
      <div class="home-logo">
        <i></i>
        <div><b>篮界</b><small>LANJIE</small></div>
      </div>
      <router-link to="/book" class="home-date">
        <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
             stroke-width="1.7" stroke-linecap="round">
          <rect x="3" y="5" width="18" height="16" rx="3" />
          <path d="M8 3v4M16 3v4M3 10h18" />
        </svg>
        今天 · {{ dateLabel }}
        <span class="caret">⌄</span>
      </router-link>
    </header>

    <div class="home-greeting">
      <small>城东体育馆 · 4 片全场</small>
      <h1>你好，{{ sessionState.me?.nickname ?? '球友' }}<br />今晚，去哪片场？</h1>
    </div>

    <section v-if="sheetOpen" class="court-sheet">
      <div class="sheet-handle"></div>
      <button class="sheet-close" aria-label="收起球场详情" @click="sheetOpen = false">✕</button>
      <small class="sheet-kicker">COURT <b>01</b> · 木地板</small>
      <h2>1 号场</h2>
      <p>今天还剩 {{ availableCount }} 个时段 · 4 片全场</p>
      <div class="sheet-cta">
        <router-link to="/book" class="sheet-primary">查看时段</router-link>
        <button class="sheet-secondary" @click="sheetOpen = false">收起</button>
      </div>
      <div class="sheet-quick">
        <router-link to="/coach">约教练</router-link>
        <router-link to="/pickup">去拼场</router-link>
        <router-link to="/ai">AI 前台</router-link>
      </div>
    </section>

    <button v-else class="sheet-reopen" @click="sheetOpen = true">球场详情</button>

    <DockBar />
  </div>
</template>
