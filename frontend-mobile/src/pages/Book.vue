<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
import { ensureLucide, renderIcons } from '@/utils/icons'

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

const selectedDay = computed(() => days.value.find((x) => x.date === selectedDate.value))
const selectedDayTitle = computed(() => {
  if (!selectedDay.value) return isReschedule.value ? '改期' : '订场'
  const date = new Date(`${selectedDate.value}T00:00:00`)
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日 ${WEEK_LABELS[date.getDay()]}`
})
const dayOpen = computed(() => selectedDay.value?.openTime ?? '10:00')
const dayClose = computed(() => selectedDay.value?.closeTime ?? '22:00')

const hours = computed<string[]>(() => {
  if (!selectedDay.value) return []
  const list: string[] = []
  for (let m = toMin(selectedDay.value.openTime); m + HOUR <= toMin(selectedDay.value.closeTime); m += HOUR) {
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
const selectedCourt = computed(() => courts.value.find((c) => c.id === selected.value[0]?.courtId))
const selectedRange = computed(() => {
  if (!selected.value.length) return ''
  const hours = selected.value.map((s) => toMin(s.startAt)).sort((a, b) => a - b)
  const contiguous = hours.every((h, i) => i === 0 || h - hours[i - 1] === HOUR)
  if (contiguous && hours.length > 1) return `${toStr(hours[0])}–${toStr(hours[hours.length - 1] + HOUR)}`
  return hours.map(toStr).join('、')
})
const barTitle = computed(() =>
  selectedCourt.value && selected.value.length
    ? `${selectedCourt.value.name} · ${selected.value.length} 小时`
    : '选一个时段开始',
)
const barSub = computed(() =>
  selected.value.length ? `${selectedDayTitle.value} · ${selectedRange.value}` : '支持连选，最多 4 小时',
)

function chipLabel(d: CalendarDay): string {
  const date = new Date(`${d.date}T00:00:00`)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const diff = Math.round((date.getTime() - today.getTime()) / 86_400_000)
  if (diff === 0) return '今天'
  if (diff === 1) return '明天'
  if (diff === 2) return '后天'
  return WEEK_LABELS[date.getDay()]
}

function cellSlot(court: CourtInfo, hour: string): SlotInfo | undefined {
  return slotMap.value.get(`${court.id}|${hour}`)
}

function isSelectable(slot: SlotInfo): boolean {
  if (slot.status !== 'AVAILABLE') return false
  return new Date(`${selectedDate.value}T${slot.startAt}:00`).getTime() > Date.now()
}

type CellState = 'sel' | 'avail' | 'off' | 'booked' | 'held' | 'closed'

function cellState(court: CourtInfo, hour: string): CellState {
  const slot = cellSlot(court, hour)
  if (!slot) return 'off'
  if (selected.value.some((s) => s.id === slot.id)) return 'sel'
  if (slot.status === 'BOOKED') return 'booked'
  if (slot.status === 'HELD') return 'held'
  if (slot.status === 'CLOSED') return 'closed'
  if (!isSelectable(slot)) return 'off'
  return 'avail'
}

function demoClass(court: CourtInfo, hour: string): string {
  const state = cellState(court, hour)
  return state === 'avail' ? 'free' : state
}

function cellMain(court: CourtInfo, hour: string): string {
  const slot = cellSlot(court, hour)
  if (!slot) return ''
  const state = cellState(court, hour)
  if (state === 'sel' || state === 'avail') return `¥${slot.priceCents / 100}`
  if (state === 'booked') return '已订'
  if (state === 'held') return '待支付'
  if (state === 'closed') return '已关'
  return '—'
}

function cellSub(court: CourtInfo, hour: string): string {
  const state = cellState(court, hour)
  if (state === 'sel' || state === 'avail') return '空'
  if (state === 'booked' || state === 'held') return '—'
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

/** AI 帮我选：优先找最早的一段可连场（最多 2 小时） */
function aiPick() {
  const available = slots.value
    .filter(isSelectable)
    .sort((a, b) => a.startAt.localeCompare(b.startAt) || a.courtId - b.courtId)
  const first = available[0]
  if (!first) {
    window.alert('这天已经没有空场了，换个日期看看')
    return
  }
  selected.value = [first]
  const next = slotMap.value.get(`${first.courtId}|${toStr(toMin(first.startAt) + HOUR)}`)
  if (next && isSelectable(next)) {
    selected.value.push(next)
  }
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
  const wantedDate = String(route.query.date ?? '')
  selectedDate.value = days.value.some((d) => d.date === wantedDate)
    ? wantedDate
    : days.value[0]?.date ?? ''
  courts.value = await fetchCourts()
  slots.value = selectedDate.value ? await fetchSlots(selectedDate.value) : []

  // 从首页 3D 球馆带过来的球场/时段
  const wantedCourt = Number(route.query.court)
  const wantedHours = String(route.query.hours ?? '')
    .split(',')
    .filter(Boolean)
    .map(Number)
  if (wantedCourt && wantedHours.length) {
    selected.value = wantedHours
      .map((hour) =>
        slots.value.find(
          (s) => s.courtId === wantedCourt && Number(s.startAt.slice(0, 2)) === hour && isSelectable(s),
        ),
      )
      .filter((s): s is SlotInfo => Boolean(s))
      .sort((a, b) => a.startAt.localeCompare(b.startAt))
  }

  await nextTick()
  // 和 demo 一样默认滚到 18:00 附近，优先展示晚场
  const scroller = document.querySelector('.grid-scroll') as HTMLElement | null
  if (scroller) scroller.scrollLeft = 8 * 64
  try {
    await ensureLucide()
    renderIcons(16)
  } catch {
    // 图标失败不影响订场
  }
})
</script>

<template>
  <div id="lanjie-proto" class="demo-book">
    <div class="phone-screen">
      <div class="sub-head">
        <button class="icon-btn" aria-label="返回" @click="router.push('/')">
          <i data-lucide="arrow-left"></i>
        </button>
        <b>{{ isReschedule ? '改期' : '订场' }}</b>
        <button class="icon-btn" aria-label="我的订单" @click="router.push('/orders')">
          <i data-lucide="scan-line"></i>
        </button>
      </div>

      <div class="book-body">
        <div class="date-strip" aria-label="选择日期">
          <button
            v-for="d in days"
            :key="d.date"
            class="date-chip"
            :class="{ active: d.date === selectedDate }"
            @click="pick(d)"
          >
            <small>{{ chipLabel(d) }}</small>
            <b>{{ Number(d.date.slice(8)) }}</b>
          </button>
        </div>

        <div class="book-summary">
          <div>
            <b>{{ selectedDayTitle }}</b>
            <small>{{ courts.length }} 片全场 · {{ dayOpen }}–{{ dayClose }}</small>
          </div>
          <button class="ai-chip" @click="aiPick">
            <i data-lucide="sparkles"></i> AI 帮我选
          </button>
        </div>

        <div class="grid-scroll">
          <div class="grid-table" :style="{ '--hours': hours.length }" role="grid" aria-label="场地时段表">
            <div class="corner">场地</div>
            <div v-for="h in hours" :key="h" class="time-head">{{ h }}</div>
            <template v-for="c in courts" :key="c.id">
              <div class="court-head" @click="router.push(`/courts/${c.id}`)">{{ c.name }}</div>
              <button
                v-for="h in hours"
                :key="`${c.id}-${h}`"
                class="cell"
                :class="demoClass(c, h)"
                :aria-pressed="cellState(c, h) === 'sel'"
                @click="tapCell(c, h)"
              >
                <b>{{ cellMain(c, h) }}</b>
                <small v-if="cellSub(c, h)">{{ cellSub(c, h) }}</small>
              </button>
            </template>
          </div>
        </div>

        <div class="grid-legend">
          <span><i class="lg free"></i>可订</span>
          <span><i class="lg booked"></i>已订</span>
          <span><i class="lg held"></i>待支付</span>
          <span><i class="lg sel"></i>已选</span>
        </div>

        <div class="book-rule">
          <div class="rule-row"><span>{{ dayOpen }}–18:00</span><b>平日 / 周末价</b></div>
          <div class="rule-row"><span>18:00–{{ dayClose }}</span><b>晚场价</b></div>
        </div>

        <div class="book-note">
          <i data-lucide="shield-check"></i>
          <div><b>锁位 15 分钟</b><small>未支付自动释放；开场前 24 小时可全额退</small></div>
        </div>
      </div>

      <div class="book-bar" aria-live="polite">
        <div class="bb-main">
          <b class="bb-title">{{ barTitle }}</b>
          <small class="bb-sub">{{ barSub }}</small>
        </div>
        <span class="bb-price">{{ selected.length ? `¥${totalCents / 100}` : '—' }}</span>
        <button class="bb-btn" :disabled="!selected.length || holding" @click="onHold">
          {{ holding ? '锁位中…' : isReschedule ? '改期' : '去支付' }}
        </button>
      </div>

      <p v-if="holdError" class="book-error">{{ holdError }}</p>

      <BookingSheet
        v-if="sheetBookingId"
        :booking-id="sheetBookingId"
        @close="sheetBookingId = null"
        @paid="onPaid"
      />
    </div>
  </div>
</template>
