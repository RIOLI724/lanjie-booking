<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DockBar from '@/components/DockBar.vue'
import BookingSheet from '@/components/BookingSheet.vue'
import {
  buyPackage, fetchCoachSlots, fetchCoaches, fetchMyPackages, holdCoach,
  type CoachInfo, type CoachPackageInfo, type CoachSlotInfo,
} from '@/api/services'
import { confirmMockPay } from '@/api/bookings'
import { fetchSlots } from '@/api/venue'
import { ApiError } from '@/api/http'

const coaches = ref<CoachInfo[]>([])
const packages = ref<CoachPackageInfo[]>([])
const slots = ref<CoachSlotInfo[]>([])
const selectedCoach = ref<CoachInfo | null>(null)
const selectedDate = ref<'今天' | '明天' | '后天'>('明天')
const selectedSlot = ref<CoachSlotInfo | null>(null)
const sheetBookingId = ref<number | null>(null)
const busy = ref(false)
const error = ref('')

const dateIso = computed(() => {
  const offset = selectedDate.value === '今天' ? 0 : selectedDate.value === '明天' ? 1 : 2
  const d = new Date(Date.now() + offset * 86_400_000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const days = ['今天', '明天', '后天'] as const

async function loadSlots() {
  selectedSlot.value = null
  if (!selectedCoach.value) return
  slots.value = await fetchCoachSlots(selectedCoach.value.id, dateIso.value)
}

async function pickCoach(coach: CoachInfo) {
  selectedCoach.value = coach
  await loadSlots()
}

async function pickDay(day: '今天' | '明天' | '后天') {
  selectedDate.value = day
  await loadSlots()
}

async function reserve() {
  if (!selectedCoach.value || !selectedSlot.value || busy.value) return
  const service = selectedCoach.value.services.find((s) => s.type === 'PRIVATE')
  if (!service) { error.value = '该教练暂不可约'; return }
  busy.value = true
  error.value = ''
  try {
    let courtSlotId: number | undefined
    if (service.includesCourt) {
      const courtSlots = await fetchSlots(dateIso.value)
      const match = courtSlots.find(
        (s) => s.status === 'AVAILABLE' && s.startAt === selectedSlot.value!.startAt.slice(11, 16),
      )
      if (!match) { error.value = '该时段场地已满，换一个时间'; return }
      courtSlotId = match.id
    }
    const hold = await holdCoach({
      coachSlotId: selectedSlot.value.id,
      serviceId: service.id,
      courtSlotId,
    })
    sheetBookingId.value = hold.bookingId
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}

async function buy(coach: CoachInfo, sessions: number) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const pay = await buyPackage(coach.id, sessions)
    await confirmMockPay(pay.payNo)
    packages.value = await fetchMyPackages()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  coaches.value = await fetchCoaches()
  packages.value = await fetchMyPackages()
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">约教练</h1>

    <div v-if="packages.length" class="card" style="margin-bottom: 10px">
      <div v-for="p in packages" :key="p.id" class="pkg-row">
        <span>{{ p.coachName }} · 剩 {{ p.remainingSessions }} 节</span>
        <small>{{ p.expiresAt.slice(0, 10) }} 到期</small>
      </div>
    </div>

    <div class="coach-grid">
      <button
        v-for="c in coaches"
        :key="c.id"
        class="card coach-card"
        :class="{ on: selectedCoach?.id === c.id }"
        @click="pickCoach(c)"
      >
        <b>{{ c.displayName }}</b>
        <small>{{ c.specialties }}</small>
        <span class="price">¥{{ c.basePriceCents / 100 }}/节</span>
      </button>
    </div>

    <template v-if="selectedCoach">
      <div class="date-strip">
        <button
          v-for="d in days"
          :key="d"
          class="date-chip"
          :class="{ active: selectedDate === d }"
          @click="pickDay(d)"
        >
          <b>{{ d }}</b>
        </button>
      </div>

      <div class="slot-grid">
        <button
          v-for="s in slots"
          :key="s.id"
          class="slot-chip"
          :class="{ on: selectedSlot?.id === s.id, off: s.status !== 'AVAILABLE' }"
          :disabled="s.status !== 'AVAILABLE'"
          @click="selectedSlot = s"
        >
          {{ s.startAt.slice(11, 16) }}
        </button>
      </div>

      <p v-if="error" class="error">{{ error }}</p>

      <div class="coach-actions">
        <button class="btn-primary" :disabled="!selectedSlot || busy" @click="reserve">
          {{ busy ? '处理中…' : `预约 ${selectedSlot ? selectedSlot.startAt.slice(11, 16) : ''}` }}
        </button>
      </div>

      <div class="pkg-buy">
        <span>课包</span>
        <button class="btn-ghost" @click="buy(selectedCoach, 5)">5 节 95 折</button>
        <button class="btn-ghost" @click="buy(selectedCoach, 10)">10 节 9 折</button>
        <button class="btn-ghost" @click="buy(selectedCoach, 20)">20 节 85 折</button>
      </div>
    </template>

    <BookingSheet v-if="sheetBookingId" :booking-id="sheetBookingId" @close="sheetBookingId = null" @paid="sheetBookingId = null" />

    <DockBar />
  </div>
</template>
