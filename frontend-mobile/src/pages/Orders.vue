<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import DockBar from '@/components/DockBar.vue'
import BookingSheet from '@/components/BookingSheet.vue'
import CheckinSheet from '@/components/CheckinSheet.vue'
import { cancelBooking, fetchBookings, type BookingInfo } from '@/api/bookings'
import { ApiError } from '@/api/http'

const router = useRouter()

const bookings = ref<BookingInfo[]>([])
const sheetFor = ref<number | null>(null)
const qrFor = ref<number | null>(null)
const now = ref(Date.now())
let timer: number | undefined

const STATUS_CHIP: Record<string, string> = {
  PENDING_PAYMENT: '待支付',
  CONFIRMED: '已确认',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  NO_SHOW: '爽约',
}

const STATUS_CLASS: Record<string, string> = {
  PENDING_PAYMENT: 'pending',
  CONFIRMED: 'confirmed',
  COMPLETED: 'done',
  CANCELLED: 'done',
  NO_SHOW: 'done',
}

const remaining = computed<Record<number, string>>(() => {
  const map: Record<number, string> = {}
  for (const b of bookings.value) {
    if (b.status !== 'PENDING_PAYMENT' || !b.holdExpiresAt) continue
    const total = Math.max(0, Math.floor((new Date(b.holdExpiresAt).getTime() - now.value) / 1000))
    map[b.id] = `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
  }
  return map
})

async function load() {
  bookings.value = await fetchBookings()
}

async function onCancel(b: BookingInfo) {
  if (b.status === 'CONFIRMED') {
    const refund = b.refundQuoteCents / 100
    const ok = window.confirm(refund > 0 ? `取消后将退款 ¥${refund}，确定取消？` : '取消将不退款（距开场不足 2 小时），确定？')
    if (!ok) return
  }
  try {
    await cancelBooking(b.id)
    await load()
  } catch (e) {
    // 取消失败静默刷新，状态以后端为准
    await load()
    if (e instanceof ApiError && e.code !== 40402) throw e
  }
}

function onReschedule(b: BookingInfo) {
  router.push(`/book?reschedule=${b.id}`)
}

onMounted(async () => {
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
  await load()
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">我的订单</h1>

    <div v-for="b in bookings" :key="b.id" class="card order-card">
      <div class="order-top">
        <span class="chip" :class="STATUS_CLASS[b.status]">{{ STATUS_CHIP[b.status] ?? b.status }}</span>
        <small>{{ b.createdAt.slice(5, 16).replace('T', ' ') }}</small>
      </div>
      <div class="order-title">{{ b.items[0]?.title }}</div>
      <div class="order-sub">
        {{ b.items.length > 1 ? `等 ${b.items.length} 条` : '' }}
        <span v-if="b.status === 'PENDING_PAYMENT' && remaining[b.id]">· 剩余 {{ remaining[b.id] }}</span>
      </div>
      <div class="order-foot">
        <b>¥{{ b.payableCents / 100 }}</b>
        <div v-if="b.status === 'PENDING_PAYMENT'" class="order-actions">
          <button :disabled="!remaining[b.id]" @click="onCancel(b)">取消</button>
          <button class="go-pay" :disabled="!remaining[b.id]" @click="sheetFor = b.id">去支付</button>
        </div>
        <div v-else-if="b.status === 'CONFIRMED'" class="order-actions">
          <button v-if="b.type === 'COURT' && b.rescheduleCount === 0" @click="onReschedule(b)">改期</button>
          <button @click="onCancel(b)">{{ b.refundQuoteCents > 0 ? `取消退 ¥${b.refundQuoteCents / 100}` : '取消' }}</button>
          <button class="go-pay" @click="qrFor = b.id">核销码</button>
        </div>
      </div>
    </div>

    <BookingSheet v-if="sheetFor" :booking-id="sheetFor" @close="sheetFor = null" @paid="load" />
    <CheckinSheet v-if="qrFor" :booking-id="qrFor" @close="qrFor = null" />

    <DockBar />
  </div>
</template>
