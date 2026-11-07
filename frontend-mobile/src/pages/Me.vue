<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import CheckinSheet from '@/components/CheckinSheet.vue'
import { loadMe, logout, sessionState } from '@/stores/session'
import { fetchMember, fetchMyPackages, type CoachPackageInfo, type MemberInfo } from '@/api/services'
import { fetchBookings, type BookingInfo } from '@/api/bookings'
import { fetchWallet } from '@/api/wallet'
import { request } from '@/api/http'
import { ensureLucide, renderIcons } from '@/utils/icons'

const router = useRouter()
const member = ref<MemberInfo | null>(null)
const walletCents = ref(0)
const packages = ref<CoachPackageInfo[]>([])
const bookings = ref<BookingInfo[]>([])
const couponCount = ref(0)
const orderFilter = ref<'all' | 'pay' | 'soon' | 'done'>('all')
const qrBookingId = ref<number | null>(null)

const discountText = computed(() => {
  const pct = member.value?.discountPct ?? 100
  return pct % 10 === 0 ? String(pct / 10) : (pct / 10).toFixed(1)
})
const packageSessions = computed(() => packages.value.reduce((sum, p) => sum + p.remainingSessions, 0))
const filteredOrders = computed(() => {
  const all = bookings.value.slice(0, 6)
  if (orderFilter.value === 'pay') return all.filter((b) => b.status === 'PENDING_PAYMENT')
  if (orderFilter.value === 'soon') return all.filter((b) => b.status === 'CONFIRMED')
  if (orderFilter.value === 'done') return all.filter((b) => ['COMPLETED', 'CANCELLED', 'NO_SHOW'].includes(b.status))
  return all
})

const STATUS_TEXT: Record<string, string> = {
  PENDING_PAYMENT: '待支付', CONFIRMED: '待到店', COMPLETED: '已完成',
  CANCELLED: '已取消', NO_SHOW: '爽约',
}

function dateText(iso: string): string {
  const d = new Date(iso)
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日 ${['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]}`
}

function orderTitle(b: BookingInfo): string {
  return b.items[0]?.title ?? (b.type === 'COACH' ? '教练课' : b.type === 'GROUP' ? '团课' : '场地')
}

function courtBadge(b: BookingInfo): string {
  if (b.type === 'COACH') return '教'
  if (b.type === 'GROUP') return '团'
  if (b.type === 'PICKUP') return '拼'
  const match = orderTitle(b).match(/\d+/)
  return match ? match[0].padStart(2, '0') : '场'
}

function statusClass(b: BookingInfo): string {
  if (b.status === 'PENDING_PAYMENT') return 'pay'
  if (b.status === 'CONFIRMED') return 'soon'
  if (b.status === 'CANCELLED' || b.status === 'NO_SHOW') return 'refund'
  return 'done'
}

function orderAction(b: BookingInfo): string {
  if (b.status === 'PENDING_PAYMENT') return '去支付'
  if (b.status === 'CONFIRMED') return '出示二维码'
  return '再来一单'
}

async function onOrderAction(b: BookingInfo) {
  if (b.status === 'PENDING_PAYMENT') {
    router.push('/orders')
    return
  }
  if (b.status === 'CONFIRMED') {
    qrBookingId.value = b.id
    return
  }
  router.push('/book')
}

async function onLogout() {
  await logout()
  router.push('/login')
}

onMounted(async () => {
  if (!sessionState.loaded) loadMe().catch(() => {})
  try {
    const [memberInfo, wallet, pkgs, list, coupons] = await Promise.all([
      fetchMember().catch(() => null),
      fetchWallet().catch(() => ({ balanceCents: 0 })),
      fetchMyPackages().catch(() => []),
      fetchBookings().catch(() => []),
      request<unknown[]>('/me/coupons', { auth: true }).catch(() => []),
    ])
    member.value = memberInfo
    walletCents.value = wallet.balanceCents
    packages.value = pkgs
    bookings.value = list
    couponCount.value = coupons.length
  } catch {
    // 部分数据失败不阻塞页面
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
  <div id="lanjie-proto" class="demo-me">
    <div class="phone-screen me-screen">
      <div class="me-head">
        <b>我的</b>
        <button class="icon-btn" aria-label="设置" @click="onLogout"><i data-lucide="settings"></i></button>
      </div>

      <div class="me-body">
        <div class="me-profile">
          <span class="me-avatar">{{ sessionState.me?.nickname?.slice(0, 1) ?? '球' }}</span>
          <div>
            <b>{{ sessionState.me?.nickname ?? '球友' }}</b>
            <small>{{ member?.levelName ?? '普通会员' }} · 订场 {{ discountText }} 折</small>
          </div>
          <button class="me-level" @click="router.push('/wallet')">会员中心 <i data-lucide="chevron-right"></i></button>
        </div>

        <div class="wallet-card">
          <div class="wallet-top"><span>账户余额</span><button @click="router.push('/wallet')">充值</button></div>
          <div class="wallet-amount">
            ¥{{ Math.floor(walletCents / 100) }}<span>.{{ String(walletCents % 100).padStart(2, '0') }}</span>
          </div>
          <div class="wallet-foot">
            <span>教练课包 {{ packageSessions }} 节 · {{ packages.length }} 个</span>
            <button @click="router.push('/wallet')">明细 <i data-lucide="chevron-right"></i></button>
          </div>
        </div>

        <div class="asset-grid">
          <div class="asset"><b>{{ packageSessions }}</b><small>教练课时</small></div>
          <div class="asset"><b>{{ packages.length }}</b><small>课包</small></div>
          <div class="asset"><b>{{ couponCount }}</b><small>优惠券</small></div>
          <div class="asset"><b>{{ member?.pointsBalance ?? 0 }}</b><small>积分</small></div>
        </div>

        <div class="me-orders">
          <div class="section-head">
            <h2>我的订单</h2>
            <button class="text-btn" @click="router.push('/orders')">全部 <i data-lucide="chevron-right"></i></button>
          </div>
          <div class="order-tabs" role="group" aria-label="订单筛选">
            <button :class="{ active: orderFilter === 'all' }" @click="orderFilter = 'all'">全部</button>
            <button :class="{ active: orderFilter === 'pay' }" @click="orderFilter = 'pay'">待支付</button>
            <button :class="{ active: orderFilter === 'soon' }" @click="orderFilter = 'soon'">待到店</button>
            <button :class="{ active: orderFilter === 'done' }" @click="orderFilter = 'done'">已完成</button>
          </div>

          <div class="order-list">
            <div v-for="b in filteredOrders" :key="b.id" class="order-row">
              <div class="order-main">
                <span class="order-court">{{ courtBadge(b) }}</span>
                <div class="order-info">
                  <b>{{ orderTitle(b) }}</b>
                  <small>{{ b.items[0] ? dateText(b.items[0].startAt) + ' · ' + b.items[0].startAt.slice(11, 16) + '–' + b.items[0].endAt.slice(11, 16) : dateText(b.createdAt) }}</small>
                </div>
                <span class="order-price">¥{{ (b.paidCents || b.payableCents) / 100 }}</span>
              </div>
              <div class="order-foot">
                <span class="status" :class="statusClass(b)">{{ STATUS_TEXT[b.status] ?? b.status }}</span>
                <button class="order-action" @click="onOrderAction(b)">{{ orderAction(b) }}</button>
              </div>
            </div>
            <p v-if="!filteredOrders.length" class="empty">这里还没有订单</p>
          </div>
        </div>

        <div class="me-links">
          <button @click="router.push('/pickup')"><span><i data-lucide="users"></i>我的拼场</span><i data-lucide="chevron-right"></i></button>
          <button @click="router.push('/orders')"><span><i data-lucide="receipt"></i>订单与退款</span><i data-lucide="chevron-right"></i></button>
          <button @click="router.push('/ai')"><span><i data-lucide="headphones"></i>联系客服</span><i data-lucide="chevron-right"></i></button>
        </div>
      </div>

      <nav class="me-tabs" aria-label="手机导航">
        <button @click="router.push('/')"><i data-lucide="home"></i><span>首页</span></button>
        <button @click="router.push('/coach')"><i data-lucide="dumbbell"></i><span>教练</span></button>
        <button @click="router.push('/pickup')"><i data-lucide="users"></i><span>拼场</span></button>
        <button class="active"><i data-lucide="user-round"></i><span>我的</span></button>
      </nav>

      <CheckinSheet v-if="qrBookingId" :booking-id="qrBookingId" @close="qrBookingId = null" />
    </div>
  </div>
</template>
