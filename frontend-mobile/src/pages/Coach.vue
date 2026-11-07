<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import BookingSheet from '@/components/BookingSheet.vue'
import {
  buyPackage,
  enrollGroupClass,
  fetchCoachSlots,
  fetchCoaches,
  fetchGroupClasses,
  fetchMyPackages,
  holdCoach,
  type CoachInfo,
  type CoachPackageInfo,
  type CoachSlotInfo,
  type GroupClassInfo,
} from '@/api/services'
import { confirmMockPay } from '@/api/bookings'
import { fetchSlots } from '@/api/venue'
import { ApiError } from '@/api/http'
import { ensureLucide, renderIcons } from '@/utils/icons'

const router = useRouter()
const coaches = ref<CoachInfo[]>([])
const packages = ref<CoachPackageInfo[]>([])
const groupClasses = ref<GroupClassInfo[]>([])
const slots = ref<CoachSlotInfo[]>([])
const selectedCoach = ref<CoachInfo | null>(null)
const selectedDate = ref<'今天' | '明天' | '后天'>('今天')
const selectedSlot = ref<CoachSlotInfo | null>(null)
const sheetBookingId = ref<number | null>(null)
const tab = ref<'private' | 'group'>('private')
const busy = ref(false)
const error = ref('')

const days = ['今天', '明天', '后天'] as const

function localDate(offset = 0): string {
  const d = new Date(Date.now() + offset * 86_400_000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

const dateIso = computed(() => {
  const offset = selectedDate.value === '今天' ? 0 : selectedDate.value === '明天' ? 1 : 2
  const d = new Date(Date.now() + offset * 86_400_000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const privateService = computed(() =>
  selectedCoach.value?.services.find((s) => s.type === 'PRIVATE') ?? null,
)
const coachPrice = computed(() => (privateService.value?.priceCents ?? selectedCoach.value?.basePriceCents ?? 0) / 100)
const barPrice = computed(() => (selectedSlot.value ? coachPrice.value : null))
const sheetOpen = computed(() => Boolean(selectedCoach.value))

function timeLabel(iso: string): string {
  const date = new Date(iso)
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:00`
}

async function loadSlots() {
  selectedSlot.value = null
  if (!selectedCoach.value) return
  slots.value = (await fetchCoachSlots(selectedCoach.value.id, dateIso.value))
    .filter((s) => new Date(s.startAt).getTime() > Date.now())
  const first = slots.value.find((s) => s.status === 'AVAILABLE')
  if (first) selectedSlot.value = first
}

async function pickCoach(coach: CoachInfo) {
  selectedCoach.value = coach
  selectedDate.value = '今天'
  await loadSlots()
}

function closeSheet() {
  selectedCoach.value = null
  selectedSlot.value = null
}

async function pickDay(day: '今天' | '明天' | '后天') {
  selectedDate.value = day
  await loadSlots()
}

async function reserve() {
  if (!selectedCoach.value || !selectedSlot.value || busy.value) return
  const service = privateService.value
  if (!service) {
    error.value = '该教练暂不可约'
    return
  }
  busy.value = true
  error.value = ''
  try {
    let courtSlotId: number | undefined
    if (service.includesCourt) {
      const courtSlots = await fetchSlots(dateIso.value)
      const match = courtSlots.find(
        (s) => s.status === 'AVAILABLE' && s.startAt === selectedSlot.value!.startAt.slice(11, 16),
      )
      if (!match) {
        error.value = '该时段场地已满，换一个时间'
        return
      }
      courtSlotId = match.id
    }
    const hold = await holdCoach({
      coachSlotId: selectedSlot.value.id,
      serviceId: service.id,
      courtSlotId,
    })
    sheetBookingId.value = hold.bookingId
    closeSheet()
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
    window.alert(`已购买 ${sessions} 节课包`)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}

async function enroll(clazz: GroupClassInfo) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const res = await enrollGroupClass(clazz.id)
    if (res.bookingId) {
      sheetBookingId.value = res.bookingId
    } else {
      window.alert('已加入候补，有人退出会通知你')
    }
    groupClasses.value = (await fetchGroupClasses(localDate()))
      .filter((g) => new Date(g.startAt).getTime() > Date.now())
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}

async function loadGroups() {
  groupClasses.value = (await fetchGroupClasses(localDate()))
    .filter((g) => new Date(g.startAt).getTime() > Date.now())
}

onMounted(async () => {
  try {
    coaches.value = await fetchCoaches()
    packages.value = await fetchMyPackages()
    await loadGroups()
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
  <div id="lanjie-proto" class="demo-coach">
    <div class="phone-screen coach-screen">
      <div class="coach-head">
        <b>约教练</b>
        <button class="icon-btn" aria-label="筛选"><i data-lucide="sliders-horizontal"></i></button>
      </div>

      <div class="coach-body">
        <div class="eyebrow coach-eyebrow"><span class="pulse-dot"></span>城东体育馆 · {{ coaches.length }} 位教练</div>
        <h1>动作练对，<br /><span>少走两年弯路</span></h1>
        <p class="coach-sub">私教或团课，按你的时间约。</p>

        <div class="coach-seg" role="group" aria-label="课程类型">
          <button :class="{ active: tab === 'private' }" @click="tab = 'private'">私教</button>
          <button :class="{ active: tab === 'group' }" @click="tab = 'group'">团课</button>
        </div>

        <div v-if="tab === 'private'" class="coach-list">
          <article v-for="c in coaches" :key="c.id" class="coach-card">
            <span class="coach-avatar">{{ c.displayName.slice(0, 1) }}</span>
            <div class="coach-info">
              <b>{{ c.displayName }}</b>
              <small>{{ c.specialties }}</small>
              <span class="coach-tags">5 / 10 / 20 节包</span>
            </div>
            <div class="coach-right"><b>{{ c.rating }}</b><small>{{ c.ratingCount }} 人</small></div>
            <div class="coach-foot">
              <span>{{ c.bio || '查看可约档期' }}</span>
              <b>¥{{ c.basePriceCents / 100 }}<small>/节</small></b>
              <button @click="pickCoach(c)">查看档期</button>
            </div>
          </article>
          <p v-if="!coaches.length" class="empty">暂时没有可约教练</p>
        </div>

        <div v-else class="coach-list">
          <article v-for="g in groupClasses" :key="g.id" class="group-card">
            <div class="group-top">
              <span class="group-date">{{ timeLabel(g.startAt) }}</span>
              <span class="pickup-format">团课</span>
            </div>
            <b>{{ g.title }}</b>
            <small>{{ g.minStudents }} 人开班 · 已报 {{ g.enrolledCount }}/{{ g.maxStudents }} · 含场地</small>
            <div class="group-foot">
              <span>¥{{ g.priceCents / 100 }}<small>/人</small></span>
              <button class="join-btn" :disabled="busy || g.status !== 'OPEN' || g.enrolledCount >= g.maxStudents"
                      @click="enroll(g)">
                {{ g.status === 'FULL' || g.enrolledCount >= g.maxStudents ? '已满' : '报名' }}
              </button>
            </div>
          </article>
          <p v-if="!groupClasses.length" class="empty">今天没有团课</p>
        </div>

        <p v-if="error" class="error">{{ error }}</p>
      </div>

      <nav class="me-tabs coach-tabs" aria-label="手机导航">
        <button @click="router.push('/')"><i data-lucide="home"></i><span>首页</span></button>
        <button class="active"><i data-lucide="dumbbell"></i><span>教练</span></button>
        <button @click="router.push('/pickup')"><i data-lucide="users"></i><span>拼场</span></button>
        <button @click="router.push('/me')"><i data-lucide="user-round"></i><span>我的</span></button>
      </nav>

      <div class="page-veil" :class="{ show: sheetOpen }" @click="closeSheet"></div>

      <section class="pickup-sheet coach-sheet" :class="{ open: sheetOpen }" aria-hidden="false" aria-label="教练档期">
        <div class="sheet-grab" @click="closeSheet"></div>
        <div class="sheet-head-simple">
          <div>
            <div class="eyebrow coach-sheet-eyebrow">PRIVATE COACH</div>
            <h2>{{ selectedCoach?.displayName }}</h2>
            <p class="coach-sheet-meta">{{ selectedCoach?.specialties }} · {{ selectedCoach?.rating }}</p>
          </div>
          <button class="sheet-close" aria-label="关闭" @click="closeSheet"><i data-lucide="x"></i></button>
        </div>

        <div class="coach-packages">
          <button class="coach-pack on">单节<small>¥{{ coachPrice }}</small></button>
          <button class="coach-pack" :disabled="busy" @click="selectedCoach && buy(selectedCoach, 5)">5 节<small>95 折</small></button>
          <button class="coach-pack" :disabled="busy" @click="selectedCoach && buy(selectedCoach, 10)">10 节<small>90 折</small></button>
          <button class="coach-pack" :disabled="busy" @click="selectedCoach && buy(selectedCoach, 20)">20 节<small>85 折</small></button>
        </div>

        <div class="form-row">
          <span>日期</span>
          <div class="seg-row">
            <button v-for="d in days" :key="d" :class="{ on: selectedDate === d }" @click="pickDay(d)">{{ d }}</button>
          </div>
        </div>
        <div class="form-row form-row-top">
          <span>时间</span>
          <div class="slot-grid coach-slot-grid">
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
        </div>
        <div class="form-row">
          <span>场地</span>
          <div class="seg-row"><button class="on">含场地</button></div>
        </div>
        <p class="form-hint">提前 24 小时免费改 / 取消；24 小时内扣 1 次课时。</p>

        <div class="coach-bar">
          <div>
            <b>{{ selectedCoach?.displayName }} · 单节</b>
            <small>{{ selectedDate }} {{ selectedSlot?.startAt.slice(11, 16) ?? '选时间' }} · 含场地</small>
          </div>
          <span>{{ barPrice !== null ? `¥${barPrice}` : '—' }}</span>
          <button class="publish-btn" :disabled="!selectedSlot || busy" @click="reserve">预约</button>
        </div>
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
