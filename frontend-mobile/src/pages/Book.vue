<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DockBar from '@/components/DockBar.vue'
import BookingSheet from '@/components/BookingSheet.vue'
import { confirmMockPay, holdSlots, rescheduleBooking } from '@/api/bookings'
import { ApiError } from '@/api/http'
import {
  fetchCalendar,
  fetchCourts,
  fetchSlots,
  type CalendarDay,
  type CourtInfo,
  type SlotInfo,
} from '@/api/venue'

const route = useRoute()
const router = useRouter()

const MAX_CONTIGUOUS = 4
const HOUR = 60
const WEEK_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

const days = ref<CalendarDay[]>([])
const courts = ref<CourtInfo[]>([])
const slots = ref<SlotInfo[]>([])
const selectedDate = ref('')
const selected = ref<SlotInfo[]>([])
const sheetBookingId = ref<number | null>(null)
const holding = ref(false)
const holdError = ref('')
const rescheduleBookingId = ref(Number(route.query.reschedule) || 0)

const isReschedule = computed(() => rescheduleBookingId.value > 0)

const toMin = (s: string) => Number(s.slice(0, 2)) * 60 + Number(s.slice(3, 5))
const toStr = (m: number) =>
  `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
const localIso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

const hours = computed<string[]>(() => {
  const day = days.value.find((x) => x.date === selectedDate.value)
  if (!day) return []
  const list: string[] = []
  for (let m = toMin(day.openTime); m + HOUR <= toMin(day.closeTime); m += HOUR) {
    list.push(toStr(m))
  }
  return list
})

const slotMap = computed(() => {
  const map = new Map<string, SlotInfo>()
  for (const s of slots.value) {
    map.set(`${s.courtId}|${s.startAt}`, s)
  }
  return map
})

const totalCents = computed(() => selected.value.reduce((sum, s) => sum + s.priceCents, 0))

function chipLabel(d: CalendarDay): string {
  const date = new Date(`${d.date}T00:00:00`)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const diff = Math.round((date.getTime() - today.getTime()) / 86_400_000)
  if (diff === 0) return '今天'
  if (diff === 1) return '明天'
  return WEEK_LABELS[date.getDay()]
}

function chipDate(d: CalendarDay): string {
  const [, m, day] = d.date.split('-')
  return `${Number(m)}.${Number(day)}`
}

function cellSlot(court: CourtInfo, hour: string): SlotInfo | undefined {
  return slotMap.value.get(`${court.id}|${hour}`)
}

function isSelectable(slot: SlotInfo): boolean {
  if (slot.status !== 'AVAILABLE') return false
  return new Date(`${selectedDate.value}T${slot.startAt}:00`).getTime() > Date.now()
}

function cellState(court: CourtInfo, hour: string): 'sel' | 'avail' | 'off' {
  const slot = cellSlot(court, hour)
  if (!slot || !isSelectable(slot)) return 'off'
  if (selected.value.some((s) => s.id === slot.id)) return 'sel'
  return 'avail'
}

function cellText(court: CourtInfo, hour: string): string {
  const slot = cellSlot(court, hour)
  if (!slot) return ''
  const state = cellState(court, hour)
  if (state === 'sel' || state === 'avail') return `¥${slot.priceCents / 100}`
  if (slot.status === 'BOOKED') return '已订'
  if (slot.status === 'HELD') return '锁定'
  return ''
}

/** 连选约束：同一场地、时间连续、最多 4 格 */
function tapCell(court: CourtInfo, hour: string) {
  const slot = cellSlot(court, hour)
  if (!slot) return
  const idx = selected.value.findIndex((s) => s.id === slot.id)
  if (idx >= 0) {
    selected.value.splice(idx, 1)
    return
  }
  if (!isSelectable(slot) || selected.value.length >= MAX_CONTIGUOUS) return
  if (selected.value.length && selected.value[0].courtId !== slot.courtId) return
  const starts = [...selected.value.map((s) => toMin(s.startAt)), toMin(slot.startAt)].sort((a, b) => a - b)
  for (let i = 1; i < starts.length; i++) {
    if (starts[i] - starts[i - 1] !== HOUR) return
  }
  selected.value.push(slot)
}

async function pick(d: CalendarDay) {
  if (d.date === selectedDate.value) return
  selectedDate.value = d.date
  selected.value = []
  slots.value = await fetchSlots(d.date)
}

async function onHold() {
  if (holding.value || !selected.value.length) return
  holding.value = true
  holdError.value = ''
  try {
    const slotIds = selected.value.map((s) => s.id)
    if (isReschedule.value) {
      // 改期：差价 > 0 需补差价支付单，这里用户点「改期」即为确认支付
      const res = await rescheduleBooking(rescheduleBookingId.value, slotIds)
      if (res.payNo) {
        await confirmMockPay(res.payNo)
      }
      router.push('/orders')
      return
    }
    const hold = await holdSlots(slotIds)
    sheetBookingId.value = hold.bookingId
  } catch (e) {
    holdError.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    holding.value = false
  }
}

async function onPaid() {
  sheetBookingId.value = null
  selected.value = []
  slots.value = selectedDate.value ? await fetchSlots(selectedDate.value) : []
}

onMounted(async () => {
  const from = new Date()
  const to = new Date(Date.now() + 6 * 86_400_000)
  days.value = await fetchCalendar(localIso(from), localIso(to))
  selectedDate.value = days.value[0]?.date ?? ''
  courts.value = await fetchCourts()
  slots.value = selectedDate.value ? await fetchSlots(selectedDate.value) : []
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">{{ isReschedule ? '改期' : '订场' }}</h1>

    <div class="date-strip">
      <button
        v-for="d in days"
        :key="d.date"
        class="date-chip"
        :class="{ active: d.date === selectedDate }"
        @click="pick(d)"
      >
        <span>{{ chipLabel(d) }}</span>
        <b>{{ chipDate(d) }}</b>
      </button>
    </div>

    <div class="book-grid">
      <div class="book-row">
        <div class="hour-label"></div>
        <div v-for="c in courts" :key="c.id" class="court-name">{{ c.name }}</div>
      </div>
      <div v-for="h in hours" :key="h" class="book-row">
        <div class="hour-label">{{ h }}</div>
        <button
          v-for="c in courts"
          :key="c.id"
          class="cell"
          :class="cellState(c, h)"
          @click="tapCell(c, h)"
        >
          {{ cellText(c, h) }}
        </button>
      </div>
    </div>

    <div v-if="selected.length" class="select-bar">
      <span :class="{ 'bar-error': holdError }">{{ holdError || `${selected.length} 格连场` }}</span>
      <div class="bar-right">
        <b>¥{{ totalCents / 100 }}</b>
        <button class="hold-btn" :disabled="holding" @click="onHold">
          {{ holding ? (isReschedule ? '改期中…' : '锁位中…') : isReschedule ? '改期' : '锁位' }}
        </button>
      </div>
    </div>

    <BookingSheet
      v-if="sheetBookingId"
      :booking-id="sheetBookingId"
      @close="sheetBookingId = null"
      @paid="onPaid"
    />

    <DockBar />
  </div>
</template>
