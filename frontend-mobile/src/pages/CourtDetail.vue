<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchCourts, fetchSlots, type SlotInfo } from '@/api/venue'

const route = useRoute()
const router = useRouter()
const courtId = computed(() => Number(route.params.id) || 1)
const courtName = ref(`${courtId.value} 号场`)
const slots = ref<SlotInfo[]>([])
const today = new Date()
const dateLabel = `${today.getMonth() + 1}/${String(today.getDate()).padStart(2, '0')}`
const localIso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

const available = computed(() =>
  slots.value.filter((s) => {
    if (s.status !== 'AVAILABLE') return false
    return new Date(`${localIso}T${s.startAt}:00`).getTime() > Date.now()
  }),
)

const earliest = computed(() => available.value[0] ?? null)

onMounted(async () => {
  try {
    const courts = await fetchCourts()
    courtName.value = courts.find((c) => c.id === courtId.value)?.name ?? `${courtId.value} 号场`
    const all = await fetchSlots(localIso)
    slots.value = all.filter((s) => s.courtId === courtId.value).sort((a, b) => a.startAt.localeCompare(b.startAt))
  } catch {
    slots.value = []
  }
})

function close() {
  if (window.history.length > 1) router.back()
  else router.push('/')
}
</script>

<template>
  <div class="page court-detail">
    <div class="home-stage" aria-hidden="true">
      <div class="home-spot"></div>
      <div class="home-line"></div>
      <div class="home-floor"><i></i></div>
    </div>
    <div class="home-demo-shade"></div>

    <header class="home-top">
      <div class="home-logo">
        <i></i>
        <div><b>篮界</b><small>LANJIE</small></div>
      </div>
      <div class="home-date">
        <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
             stroke-width="1.7" stroke-linecap="round">
          <rect x="3" y="5" width="18" height="16" rx="3" />
          <path d="M8 3v4M16 3v4M3 10h18" />
        </svg>
        今天 · {{ dateLabel }}
      </div>
    </header>

    <section class="court-sheet court-sheet-detail">
      <div class="sheet-handle"></div>
      <button class="sheet-close" aria-label="返回" @click="close">✕</button>
      <small class="sheet-kicker">COURT 0{{ courtId }} · 木地板</small>
      <h2>{{ courtName }}</h2>
      <p>今天还剩 {{ available.length }} 个时段 · 4 片全场</p>
      <div class="sheet-cta">
        <router-link :to="`/book?court=${courtId}`" class="sheet-primary">查看时段</router-link>
        <button class="sheet-secondary" @click="close">返回</button>
      </div>
      <div class="sheet-quick" v-if="earliest">
        <span class="sheet-next">最近可订 {{ earliest.startAt }} · ¥{{ earliest.priceCents / 100 }}/时</span>
      </div>
    </section>
  </div>
</template>
