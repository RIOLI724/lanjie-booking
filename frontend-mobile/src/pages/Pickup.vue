<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import BookingSheet from '@/components/BookingSheet.vue'
import { createPickup, fetchPickups, joinPickup, type PickupGameInfo } from '@/api/services'
import { fetchSlots, type SlotInfo } from '@/api/venue'
import { ApiError } from '@/api/http'
import { ensureLucide, renderIcons } from '@/utils/icons'

const router = useRouter()
const games = ref<PickupGameInfo[]>([])
const slots = ref<SlotInfo[]>([])
const format = ref('all')
const selectedSlotId = ref(0)
const createFormat = ref('3v3')
const showCreate = ref(false)
const sheetBookingId = ref<number | null>(null)
const busy = ref(false)
const error = ref('')

const todayIso = (() => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})()

const filtered = computed(() =>
  format.value === 'all' ? games.value : games.value.filter((g) => g.format === format.value),
)

function courtNo(game: PickupGameInfo): string {
  const match = game.courtName.match(/\d+/)
  return match ? match[0].padStart(2, '0') : game.courtName.slice(0, 2)
}

function timeText(game: PickupGameInfo): string {
  const start = new Date(game.startAt)
  const end = new Date(game.endAt)
  const today = new Date()
  const sameDay = start.toDateString() === today.toDateString()
  const prefix = sameDay ? '今晚' : '明天'
  return `${prefix} ${String(start.getHours()).padStart(2, '0')}:${String(start.getMinutes()).padStart(2, '0')}–${String(end.getHours()).padStart(2, '0')}:${String(end.getMinutes()).padStart(2, '0')}`
}

function faces(game: PickupGameInfo): string[] {
  return game.participants.slice(0, 3).map((p) => p.nickname.slice(0, 1))
}

function extraFaces(game: PickupGameInfo): string {
  const extra = game.participants.length - 3
  return extra > 0 ? `+${extra}` : ''
}

function hostName(game: PickupGameInfo): string {
  return game.isInitator ? '我发起的' : `${game.participants[0]?.nickname ?? '球友'} 发起`
}

async function load() {
  games.value = await fetchPickups(todayIso)
  slots.value = (await fetchSlots(todayIso)).filter(
    (s) => s.status === 'AVAILABLE' && new Date(`${todayIso}T${s.startAt}:00`).getTime() > Date.now(),
  )
  if (!selectedSlotId.value && slots.value.length) selectedSlotId.value = slots.value[0].id
}

async function join(game: PickupGameInfo) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const hold = await joinPickup(game.id)
    sheetBookingId.value = hold.bookingId
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}

async function create() {
  if (!selectedSlotId.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const hold = await createPickup(selectedSlotId.value, createFormat.value, '')
    showCreate.value = false
    sheetBookingId.value = hold.bookingId
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  try {
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  }
  await nextTick()
  try {
    await ensureLucide()
    renderIcons(16)
  } catch {
    // ignore
  }
})
</script>

<template>
  <div id="lanjie-proto" class="demo-pickup">
    <div class="phone-screen screen-dark pickup-screen">
      <div class="pickup-head">
        <button class="glass-round" aria-label="返回" @click="router.push('/')">
          <i data-lucide="arrow-left"></i>
        </button>
        <b>拼场</b>
        <button class="pickup-create" @click="showCreate = true"><i data-lucide="plus"></i>发起</button>
      </div>

      <div class="pickup-body">
        <div class="eyebrow"><span class="pulse-dot"></span>今晚 · 城东体育馆</div>
        <h1>找个人，<br /><span>一起打</span></h1>
        <p class="pickup-sub">3v3 / 4v4 / 5v5，按人头 AA；缺人的局都在这。</p>

        <div class="pickup-filters" role="group" aria-label="赛制筛选">
          <button v-for="f in ['all', '3v3', '4v4', '5v5']" :key="f"
                  class="filter" :class="{ active: format === f }" @click="format = f">
            {{ f === 'all' ? '全部' : f }}
          </button>
        </div>

        <p v-if="error" class="page-error">{{ error }}</p>

        <div class="pickup-list">
          <article v-for="g in filtered" :key="g.id" class="pickup-card" :class="{ full: g.status !== 'OPEN' }">
            <div class="pickup-top">
              <span class="pickup-court">{{ courtNo(g) }}</span>
              <div class="pickup-when"><b>{{ g.courtName }}</b><small>{{ timeText(g) }}</small></div>
              <span class="pickup-format">{{ g.format }}</span>
            </div>
            <div class="pickup-mid">
              <span class="pickup-need" :class="{ muted: g.status !== 'OPEN' }">
                {{ g.status === 'OPEN' ? `缺 ${g.totalPlayers - g.joinedPlayers} 人` : '已满' }}
              </span>
              <span class="pickup-price">¥{{ g.pricePerPersonCents / 100 }}<small>/人</small></span>
            </div>
            <div class="pickup-bottom">
              <div class="pickup-faces">
                <span v-for="(f, i) in faces(g)" :key="i">{{ f }}</span>
                <span v-if="extraFaces(g)">{{ extraFaces(g) }}</span>
              </div>
              <span class="pickup-host">{{ hostName(g) }}</span>
              <button class="join-btn" :disabled="busy || g.status !== 'OPEN'" @click="join(g)">
                {{ g.status === 'OPEN' ? '加入' : '已满' }}
              </button>
            </div>
          </article>
        </div>

        <p v-if="!filtered.length" class="empty">今晚还没有拼场，发起一个？</p>
      </div>

      <div class="home-dock pickup-dock">
        <nav class="dock-nav" aria-label="手机导航">
          <button class="dock-item" @click="router.push('/')"><i data-lucide="home"></i><span>首页</span></button>
          <button class="dock-item" @click="router.push('/coach')"><i data-lucide="dumbbell"></i><span>教练</span></button>
          <button class="dock-item active"><i data-lucide="users"></i><span>拼场</span></button>
          <button class="dock-item" @click="router.push('/me')"><i data-lucide="user-round"></i><span>我的</span></button>
        </nav>
      </div>

      <div class="page-veil" :class="{ show: showCreate }" @click="showCreate = false"></div>

      <section class="pickup-sheet" :class="{ open: showCreate }" aria-label="发起拼场">
        <div class="sheet-grab" @click="showCreate = false"></div>
        <div class="sheet-head-simple">
          <h2>发起拼场</h2>
          <button class="sheet-close" aria-label="关闭" @click="showCreate = false"><i data-lucide="x"></i></button>
        </div>
        <div class="form-row form-row-top">
          <span>场地 / 时间</span>
          <select v-model.number="selectedSlotId" class="slot-select">
            <option :value="0" disabled>请选择</option>
            <option v-for="s in slots" :key="s.id" :value="s.id">
              {{ s.startAt }}–{{ s.endAt }} · ¥{{ s.priceCents / 100 }}
            </option>
          </select>
        </div>
        <div class="form-row">
          <span>赛制</span>
          <div class="seg-row">
            <button v-for="f in ['3v3', '4v4', '5v5']" :key="f" :class="{ on: createFormat === f }"
                    @click="createFormat = f">{{ f }}</button>
          </div>
        </div>
        <button class="publish-btn" :disabled="!selectedSlotId || busy" @click="create">
          {{ busy ? '处理中…' : '发起并支付' }}
        </button>
        <p class="form-hint">拼场挂在你的场地订单上；开场前 24 小时可退。</p>
      </section>

      <BookingSheet
        v-if="sheetBookingId"
        :booking-id="sheetBookingId"
        @close="sheetBookingId = null"
        @paid="sheetBookingId = null"
      />
    </div>
  </div>
</template>
