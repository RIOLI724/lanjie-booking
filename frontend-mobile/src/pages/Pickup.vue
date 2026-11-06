<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DockBar from '@/components/DockBar.vue'
import BookingSheet from '@/components/BookingSheet.vue'
import { createPickup, fetchPickups, joinPickup, type PickupGameInfo } from '@/api/services'
import { fetchSlots, type SlotInfo } from '@/api/venue'
import { ApiError } from '@/api/http'

const games = ref<PickupGameInfo[]>([])
const slots = ref<SlotInfo[]>([])
const format = ref('all')
const selectedSlotId = ref(0)
const createFormat = ref('3v3')
const showCreate = ref(false)
const sheetBookingId = ref<number | null>(null)
const busy = ref(false)
const error = ref('')

const tomorrow = (() => {
  const d = new Date(Date.now() + 86_400_000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})()

const filtered = computed(() =>
  format.value === 'all' ? games.value : games.value.filter((g) => g.format === format.value),
)

async function load() {
  games.value = await fetchPickups(tomorrow)
  slots.value = (await fetchSlots(tomorrow)).filter((s) => s.status === 'AVAILABLE')
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

onMounted(load)
</script>

<template>
  <div class="page">
    <h1 class="page-title">拼场</h1>

    <div class="seg">
      <button v-for="f in ['all', '3v3', '4v4', '5v5']" :key="f"
              :class="{ active: format === f }" @click="format = f">
        {{ f === 'all' ? '全部' : f }}
      </button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <div v-for="g in filtered" :key="g.id" class="card pickup-card">
      <div class="pickup-top">
        <b>{{ g.format }} · {{ g.courtName }}</b>
        <span class="chip" :class="g.status === 'OPEN' ? 'ok' : 'warn'">
          {{ g.status === 'OPEN' ? '可加入' : '已满' }}
        </span>
      </div>
      <div class="pickup-sub">{{ g.startAt.slice(5, 16).replace('T', ' ') }} · {{ g.joinedPlayers }}/{{ g.totalPlayers }} 人</div>
      <div class="pickup-foot">
        <b>¥{{ g.pricePerPersonCents / 100 }}<small>/人</small></b>
        <button v-if="g.status === 'OPEN'" class="btn-primary" style="width: auto; padding: 8px 16px" :disabled="busy" @click="join(g)">
          加入
        </button>
      </div>
    </div>
    <p v-if="!filtered.length" class="empty">明天还没有拼场，发起一个？</p>

    <button class="btn-ghost" style="width: 100%; padding: 12px; margin-top: 10px" @click="showCreate = true">
      发起拼场
    </button>

    <DockBar />

    <div v-if="showCreate" class="veil" @click="showCreate = false"></div>
    <section v-if="showCreate" class="sheet">
      <div class="sheet-head"><b>发起拼场</b><button class="sheet-close" @click="showCreate = false">✕</button></div>
      <div class="seg">
        <button v-for="f in ['3v3', '4v4', '5v5']" :key="f"
                :class="{ active: createFormat === f }" @click="createFormat = f">{{ f }}</button>
      </div>
      <label class="field" style="margin: 10px 0">
        <span>选场地时段（明天）</span>
        <select v-model.number="selectedSlotId" class="slot-select">
          <option :value="0" disabled>请选择</option>
          <option v-for="s in slots" :key="s.id" :value="s.id">
            {{ s.startAt }}-{{ s.endAt }} ¥{{ s.priceCents / 100 }}
          </option>
        </select>
      </label>
      <button class="btn-primary" :disabled="!selectedSlotId || busy" @click="create">
        {{ busy ? '处理中…' : '发起并支付' }}
      </button>
    </section>

    <BookingSheet v-if="sheetBookingId" :booking-id="sheetBookingId" @close="sheetBookingId = null" @paid="sheetBookingId = null" />
  </div>
</template>
