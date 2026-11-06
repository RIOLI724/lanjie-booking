<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { cancelBooking, confirmMockPay, fetchBooking, payBooking, type BookingInfo } from '@/api/bookings'
import { ApiError } from '@/api/http'

const props = defineProps<{ bookingId: number }>()
const emit = defineEmits<{ close: []; paid: [] }>()

const booking = ref<BookingInfo | null>(null)
const success = ref(false)
const busy = ref(false)
const error = ref('')
const now = ref(Date.now())
let timer: number | undefined

const remainingMs = computed(() => {
  if (!booking.value?.holdExpiresAt) return 0
  return Math.max(0, new Date(booking.value.holdExpiresAt).getTime() - now.value)
})

const countdown = computed(() => {
  const total = Math.floor(remainingMs.value / 1000)
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
})

const expired = computed(() => remainingMs.value <= 0)

onMounted(async () => {
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
  try {
    booking.value = await fetchBooking(props.bookingId)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  }
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})

async function onPay() {
  if (!booking.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const pay = await payBooking(booking.value.id, 'WECHAT_MOCK')
    await confirmMockPay(pay.payNo)
    success.value = true
    emit('paid')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}

async function onCancel() {
  if (!booking.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await cancelBooking(booking.value.id)
    emit('close')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="veil" @click="emit('close')"></div>
  <div class="sheet">
    <div class="sheet-head">
      <b>{{ success ? '支付完成' : '确认订单' }}</b>
      <button class="sheet-close" @click="emit('close')">✕</button>
    </div>

    <template v-if="booking">
      <div v-if="!success" class="sheet-items">
        <div v-for="item in booking.items" :key="item.startAt" class="sheet-item">
          <span>{{ item.title }}</span>
          <small>¥{{ item.priceCents / 100 }}</small>
        </div>
      </div>

      <div v-if="!success" class="sheet-total">
        <span v-if="!expired" class="countdown">{{ countdown }}</span>
        <b>¥{{ booking.payableCents / 100 }}</b>
      </div>

      <p v-if="error" class="error">{{ error }}</p>

      <div v-if="!success" class="sheet-actions">
        <button class="btn-ghost-lg" :disabled="busy" @click="onCancel">取消</button>
        <button class="btn-primary" :disabled="busy || expired" @click="onPay">
          {{ busy ? '支付中…' : '模拟微信支付' }}
        </button>
      </div>

      <div v-else class="success-view">
        <div class="mark">✓</div>
        <b>¥{{ booking.paidCents / 100 }}</b>
        <div class="sheet-actions" style="margin-top: 14px">
          <button class="btn-primary" @click="emit('close')">完成</button>
        </div>
      </div>
    </template>
  </div>
</template>
