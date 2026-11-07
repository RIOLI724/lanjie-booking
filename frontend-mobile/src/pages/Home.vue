<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import DockBar from '@/components/DockBar.vue'
import { loadMe, sessionState } from '@/stores/session'
import { fetchSlots, type SlotInfo } from '@/api/venue'
import courtMap from '@/assets/court-map.png'

const router = useRouter()
const slots = ref<SlotInfo[]>([])
const view = ref(0)
const selectedCourt = ref(1)
const today = new Date()
const dateLabel = `${today.getMonth() + 1}/${String(today.getDate()).padStart(2, '0')}`
const localIso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

const availableSlots = computed(() =>
  slots.value
    .filter((s) => s.status === 'AVAILABLE' && new Date(`${localIso}T${s.startAt}:00`).getTime() > Date.now())
    .sort((a, b) => a.startAt.localeCompare(b.startAt) || a.courtId - b.courtId),
)

const freeCourts = computed(() => new Set(availableSlots.value.map((s) => s.courtId)).size)

const courtSlot = computed(() =>
  availableSlots.value.find((s) => s.courtId === selectedCourt.value) ?? null,
)

const earliestLabel = computed(() => {
  if (!courtSlot.value) return '今天已满'
  return `${courtSlot.value.startAt} 后可订 · ¥${courtSlot.value.priceCents / 100}/时`
})

const viewStyle = computed(() => {
  if (view.value === 1) return 'scale(1.08) translateX(-3%) rotate(-1.2deg)'
  if (view.value === 2) return 'scale(1.05) translateY(-2%) rotate(1.2deg)'
  return 'scale(1)'
})

function switchView() {
  view.value = (view.value + 1) % 3
  selectedCourt.value = (selectedCourt.value % 4) + 1
}

onMounted(async () => {
  if (!sessionState.loaded) {
    loadMe().catch(() => {})
  }
  try {
    slots.value = await fetchSlots(localIso)
  } catch {
    slots.value = []
  }
})
</script>

<template>
  <div class="page home-v3">
    <header class="v3-top">
      <div class="home-logo v3-logo">
        <i></i>
        <div><b>篮界</b><small>LANJIE</small></div>
      </div>
      <router-link to="/book" class="v3-date">
        <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
             stroke-width="1.7" stroke-linecap="round">
          <rect x="3" y="5" width="18" height="16" rx="3" />
          <path d="M8 3v4M16 3v4M3 10h18" />
        </svg>
        今天 · {{ dateLabel }}
        <span class="caret">⌄</span>
      </router-link>
    </header>

    <div class="v3-hello">
      <small>城东体育馆 · 4 片全场</small>
      <h1>你好，{{ sessionState.me?.nickname ?? '球友' }}</h1>
    </div>

    <section class="venue-card">
      <div class="venue-head">
        <div><b>3D 球馆</b><span>4 片全场</span></div>
        <em>空 {{ freeCourts }} 片</em>
      </div>
      <div class="venue-map" @click="router.push('/book')">
        <img :src="courtMap" :style="{ transform: viewStyle }" alt="3D 球馆" />
      </div>
      <div class="venue-foot">
        <span>{{ selectedCourt }}号场 · {{ earliestLabel }}</span>
        <button type="button" @click="switchView">换个视角</button>
      </div>
    </section>

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
