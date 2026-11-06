<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import DockBar from '@/components/DockBar.vue'
import BookingSheet from '@/components/BookingSheet.vue'
import CheckinSheet from '@/components/CheckinSheet.vue'
import { cancelBooking, fetchBookings, type BookingInfo } from '@/api/bookings'
import { ApiError } from '@/api/http'

const bookings = ref<BookingInfo[]>([])
const filter = ref<'all' | 'pending' | 'active' | 'closed'>('all')
const sheetFor = ref<number | null>(null)
const qrFor = ref<number | null>(null)
const now = ref(Date.now())
let timer: number | undefined

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

const filtered = computed(() => {
  if (filter.value === 'pending') return bookings.value.filter((b) => b.status === 'PENDING_PAYMENT')
  if (filter.value === 'active') return bookings.value.filter((b) => b.status === 'CONFIRMED')
  if (filter.value === 'closed') return bookings.value.filter((b) => ['COMPLETED', 'CANCELLED', 'NO_SHOW'].includes(b.status))
  return bookings.value
})

function dateLabel(iso: string): string {
  const date = new Date(`${iso.slice(0, 10)}T00:00:00`)
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const diff = Math.round((date.getTime() - today.getTime()) / 86_400_000)
  const prefix = diff === 0 ? '今天' : diff === 1 ? '明天' : diff === 2 ? '后天' : WEEK[date.getDay()]
  return `${prefix} · ${date.getMonth() + 1} 月 ${date.getDate()} 日`
}

function timeRange(item: { startAt: string; endAt: string }): string {
  return `${item.startAt.slice(11, 16)} – ${item.endAt.slice(11, 16)}`
}

function placeName(title: string): string {
  const first = title.split(' ')[0]
  return first || '场地'
}

function countdownOf(booking: BookingInfo): string {
  if (!booking.holdExpiresAt) return ''
  const total = Math.max(0, Math.floor((new Date(booking.holdExpiresAt).getTime() - now.value) / 1000))
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
}

function statusChip(booking: BookingInfo): { text: string; cls: string } {
  if (booking.status === 'PENDING_PAYMENT') return { text: '待支付', cls: 'pending' }
  if (booking.status === 'CONFIRMED') return { text: '已支付', cls: 'confirmed' }
  if (booking.status === 'COMPLETED') return { text: '已完成', cls: 'done' }
  if (booking.status === 'NO_SHOW') return { text: '爽约', cls: 'bad' }
  return { text: '已取消', cls: 'done' }
}

/** 每单只回答一个问题：现在该做什么 */
function hintOf(booking: BookingInfo): string {
  if (booking.status === 'PENDING_PAYMENT') {
    const left = countdownOf(booking)
    return left && left !== '00:00' ? `${left} 内未支付会自动释放场地` : '锁位已超时，请重新选场'
  }
  if (booking.status === 'CONFIRMED') return '到店后出示核销码，开场前 30 分钟可核销'
  if (booking.status === 'COMPLETED') return '已完成，欢迎下次再来'
  if (booking.status === 'NO_SHOW') return '未到场，已记录一次爽约'
  return '已取消'
}

function priceLabel(booking: BookingInfo): string {
  return booking.status === 'CONFIRMED' || booking.status === 'COMPLETED'
    ? `实付 ¥${booking.paidCents / 100}`
    : `金额 ¥${booking.payableCents / 100}`
}

async function load() {
  bookings.value = await fetchBookings()
}

async function onCancel(b: BookingInfo) {
  if (b.status === 'CONFIRMED') {
    const refund = b.refundQuoteCents / 100
    const ok = window.confirm(refund > 0 ? `现在取消退 ¥${refund}，确定吗？` : '距开场不足 2 小时，取消不退钱，确定吗？')
    if (!ok) return
  }
  try {
    await cancelBooking(b.id)
    await load()
  } catch (e) {
    await load()
    if (e instanceof ApiError && e.code !== 40402) throw e
  }
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

    <div class="filter-chips">
      <button :class="{ active: filter === 'all' }" @click="filter = 'all'">全部</button>
      <button :class="{ active: filter === 'pending' }" @click="filter = 'pending'">待支付</button>
      <button :class="{ active: filter === 'active' }" @click="filter = 'active'">已支付</button>
      <button :class="{ active: filter === 'closed' }" @click="filter = 'closed'">已结束</button>
    </div>

    <div v-for="b in filtered" :key="b.id" class="card order-card order-card-v2">
      <div class="order-top">
        <span class="order-date">{{ dateLabel(b.items[0]?.startAt ?? b.createdAt) }}</span>
        <span class="chip" :class="statusChip(b).cls">{{ statusChip(b).text }}</span>
      </div>

      <div class="order-main-line">
        <b>{{ timeRange(b.items[0] ?? { startAt: '', endAt: '' }) }}</b>
        <span v-if="b.items[0]">{{ placeName(b.items[0].title) }}<template v-if="b.items.length > 1"> · 连 {{ b.items.length }} 小时</template></span>
      </div>

      <div class="order-bottom-line">
        <span class="order-price">{{ priceLabel(b) }}</span>
        <span class="order-hint">{{ hintOf(b) }}</span>
      </div>

      <div v-if="b.status === 'PENDING_PAYMENT'" class="order-actions">
        <button @click="onCancel(b)">取消</button>
        <button class="go-pay" @click="sheetFor = b.id">去支付</button>
      </div>
      <div v-else-if="b.status === 'CONFIRMED'" class="order-actions">
        <button v-if="b.type === 'COURT' && b.rescheduleCount === 0" @click="$router.push(`/book?reschedule=${b.id}`)">改期</button>
        <button @click="onCancel(b)">{{ b.refundQuoteCents > 0 ? `取消退 ¥${b.refundQuoteCents / 100}` : '取消' }}</button>
        <button class="go-pay" @click="qrFor = b.id">出示核销码</button>
      </div>
    </div>

    <p v-if="!filtered.length" class="empty">
      这里还没有订单
      <router-link to="/book" class="empty-link">去订一场</router-link>
    </p>

    <BookingSheet v-if="sheetFor" :booking-id="sheetFor" @close="sheetFor = null" @paid="load" />
    <CheckinSheet v-if="qrFor" :booking-id="qrFor" @close="qrFor = null" />

    <DockBar />
  </div>
</template>
