<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { fetchCalendar, fetchSlots, type SlotInfo } from '@/api/venue'
// @ts-ignore demo 原版 JS 引擎
import { mountArenaEngine } from '@/components/arenaEngine.js'

const router = useRouter()
const HOURS = [10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21]
const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

interface DemoDay {
  key: string
  label: string
  date: string
  week: string
  weekend: boolean
  iso: string
}

const days = ref<DemoDay[]>([])
const freeCourts = ref(0)
const today = new Date()
const dateLabel = `${today.getMonth() + 1}/${String(today.getDate()).padStart(2, '0')}`
let sheetEl: HTMLElement | null = null

const freeLabel = computed(() => `${freeCourts.value} 片场`)

const localIso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

function toHours(slots: SlotInfo[], status: string): Record<string, number[]> {
  const map: Record<string, number[]> = {}
  for (let court = 1; court <= 4; court++) {
    map[String(court)] = slots
      .filter((s) => s.courtId === court && s.status === status)
      .map((s) => Number(s.startAt.slice(0, 2)))
      .filter((h) => HOURS.includes(h))
      .sort((a, b) => a - b)
  }
  return map
}

async function load() {
  const labels = ['今天', '明天', '后天']
  const calendar = await fetchCalendar(localIso(today), localIso(new Date(Date.now() + 2 * 86_400_000)))
  const picked = calendar.slice(0, 3)
  const allSlots: SlotInfo[][] = await Promise.all(picked.map((d) => fetchSlots(d.date)))
  const grid: Record<string, { booked: Record<string, number[]>; held: Record<string, number[]> }> = {}
  const priceLookup: Record<string, Record<number, number>> = {}

  days.value = picked.map((d, index) => {
    const date = new Date(`${d.date}T00:00:00`)
    return {
      key: d.date.slice(5),
      label: labels[index] ?? WEEK[date.getDay()],
      date: `${date.getMonth() + 1} 月 ${date.getDate()} 日`,
      week: WEEK[date.getDay()],
      weekend: date.getDay() === 0 || date.getDay() === 6,
      iso: d.date,
    }
  })

  picked.forEach((d, index) => {
    const key = d.date.slice(5)
    const slots = allSlots[index] ?? []
    grid[key] = { booked: toHours(slots, 'BOOKED'), held: toHours(slots, 'HELD') }
    const prices: Record<number, number> = {}
    for (const slot of slots) {
      if (slot.courtId === 1) prices[Number(slot.startAt.slice(0, 2))] = slot.priceCents / 100
    }
    priceLookup[key] = prices
  })

  const freeSet = new Set<number>()
  for (const slot of allSlots[0] ?? []) {
    if (slot.status !== 'AVAILABLE') continue
    const hour = Number(slot.startAt.slice(0, 2))
    if (!HOURS.includes(hour)) continue
    if (new Date(`${picked[0].date}T${slot.startAt}:00`).getTime() <= Date.now()) continue
    freeSet.add(slot.courtId)
  }
  freeCourts.value = freeSet.size;

  (window as any).LANJIE_DATA = {
    DAYS: days.value.map((d) => ({ key: d.key, label: d.label, date: d.date, week: d.week, weekend: d.weekend })),
    HOURS,
    GRID: grid,
    priceFor: (day: DemoDay, hour: number) =>
      priceLookup[day.key]?.[hour] ?? (hour >= 18 ? 160 : 120),
  }
}

async function ensureLucide() {
  if ((window as any).lucide) return
  await new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = '/lucide-1.17.0.js'
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('icon script failed'))
    document.head.appendChild(script)
  })
}

function renderIcons() {
  ;(window as any).lucide?.createIcons({ attrs: { width: 16, height: 16 } })
}

function onSheetClickCapture(event: Event) {
  const target = event.target as HTMLElement
  const pay = target.closest('.bb-btn') as HTMLButtonElement | null
  if (!pay || pay.disabled) return
  event.preventDefault()
  event.stopPropagation()
  const court = Number(document.getElementById('arenaCourtNo')?.textContent ?? 1)
  const active = document.querySelector('[data-arena-day].active') as HTMLElement | null
  const day = active?.getAttribute('data-arena-day') ?? ''
  const iso = days.value.find((d) => d.key === day)?.iso ?? ''
  const hours = Array.from(document.querySelectorAll('.arena-slot.sel'))
    .map((el) => el.getAttribute('data-arena-hour'))
    .filter(Boolean)
    .join(',')
  router.push({ path: '/book', query: { court: String(court), date: iso, hours } })
}

onMounted(async () => {
  try {
    await load()
  } catch {
    freeCourts.value = 0
  }
  await nextTick()
  try {
    await ensureLucide()
  } catch {
    // 图标加载失败不影响 3D
  }
  renderIcons()
  try {
    mountArenaEngine()
  } catch {
    // 引擎内部会处理 WebGL 不可用
  }
  await nextTick()
  renderIcons()
  sheetEl = document.getElementById('arenaSheet')
  sheetEl?.addEventListener('click', onSheetClickCapture, true)
})

onUnmounted(() => {
  ;(window as any).lanjieArenaStop?.()
  sheetEl?.removeEventListener('click', onSheetClickCapture, true)
})
</script>

<template>
  <div id="lanjie-proto" class="lj-home">
    <div id="view-arena" class="active lj-view">
      <div class="phone-screen screen-dark arena-screen" id="arenaScreen">
      <canvas id="arenaCanvas" aria-label="3D 球馆场景"></canvas>
      <div class="arena-vignette" aria-hidden="true"></div>

      <header class="home-top arena-top">
        <div class="brand-lockup">
          <span class="brand-orb" aria-hidden="true"></span>
          <div><b>篮界</b><small>LANJIE</small></div>
        </div>
        <button class="glass-pill" aria-label="选择日期" @click="router.push('/book')">
          <i data-lucide="calendar-days"></i>
          <span>今天 · {{ dateLabel }}</span>
          <i data-lucide="chevron-down"></i>
        </button>
      </header>

      <div class="home-title arena-title" id="arenaTitle">
        <div class="eyebrow"><span class="pulse-dot"></span>城东体育馆 · 余量实时</div>
        <h1>今晚，<span>{{ freeLabel }}</span><br />空着</h1>
        <p>拖动旋转 · 单击球场，镜头会飞过去</p>
      </div>

      <div class="court-labels" id="courtLabels" aria-label="球场"></div>

      <div class="arena-hint" id="arenaHint">
        <i data-lucide="move-3d"></i><span>拖动旋转 · 双指缩放</span>
      </div>

      <div class="home-dock arena-dock" id="arenaDock">
        <button class="ai-orb" @click="router.push('/ai')">
          <span class="orb"><i data-lucide="sparkles"></i></span>
          <span class="orb-copy"><b>AI 前台</b><small>问一句就能订</small></span>
          <span class="orb-arrow"><i data-lucide="arrow-up-right"></i></span>
        </button>
        <nav class="dock-nav" aria-label="手机导航">
          <button class="dock-item active"><i data-lucide="home"></i><span>首页</span></button>
          <button class="dock-item" @click="router.push('/coach')"><i data-lucide="dumbbell"></i><span>教练</span></button>
          <button class="dock-item" @click="router.push('/pickup')"><i data-lucide="users"></i><span>拼场</span></button>
          <button class="dock-item" @click="router.push('/me')"><i data-lucide="user-round"></i><span>我的</span></button>
        </nav>
      </div>

      <section class="arena-sheet" id="arenaSheet" aria-hidden="true" aria-label="球场时段">
        <div class="sheet-grab" id="arenaGrab" aria-hidden="true"></div>
        <div class="arena-sheet-head">
          <div>
            <div class="eyebrow">COURT <span id="arenaCourtNo">01</span> · 木地板</div>
            <h2 id="arenaCourtName">1 号场</h2>
            <p id="arenaCourtMeta">今天 还剩 11 个时段 · 4 片全场</p>
          </div>
          <button class="sheet-close" id="arenaClose" aria-label="返回全场">
            <i data-lucide="x"></i>
          </button>
        </div>

        <div class="date-seg arena-dates" role="group" aria-label="选择日期">
          <button v-for="(d, index) in days" :key="d.key" class="seg" :class="{ active: index === 0 }"
                  :data-arena-day="d.key">
            {{ d.label }} <b>{{ Number(d.key.slice(3)) }}</b>
          </button>
        </div>

        <div class="ai-suggest arena-ai">
          <span class="ai-suggest-icon"><i data-lucide="sparkles"></i></span>
          <div><b>AI 建议</b><small>点一个球场，镜头会飞过去；选好时段可直接锁位。</small></div>
          <button class="mini-btn" data-arena-suggest>用这个</button>
        </div>

        <div class="arena-slots" id="arenaSlots" role="grid" aria-label="球场时段"></div>

        <div class="arena-price-note">
          <span>10:00–18:00 <b id="arenaDayPrice">¥120/时</b></span>
          <span>18:00–22:00 <b>¥160/时</b></span>
          <span class="price-note-lock"><i data-lucide="shield-check"></i>锁位 15 分钟</span>
        </div>

        <div class="arena-bar" id="arenaBar" aria-live="polite">
          <div class="bb-main">
            <b class="bb-title">选一个时段</b>
            <small class="bb-sub">点上面的时段，可连选</small>
          </div>
          <span class="bb-price">—</span>
          <button class="bb-btn" disabled>去支付</button>
        </div>
        <div class="arena-toast" id="arenaToast" role="status"></div>
      </section>
      </div>
    </div>
  </div>
</template>
