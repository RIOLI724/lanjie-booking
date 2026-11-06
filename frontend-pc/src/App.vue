<script setup lang="ts">
import { onMounted, ref } from 'vue'
import CourtBackdrop from './components/CourtBackdrop.vue'

const BASE = '/api/v1'
const token = ref(localStorage.getItem('lanjie.admin.token') ?? '')
const phone = ref('13800000000')
const password = ref('admin123')
const error = ref('')
const loading = ref(false)
const tab = ref<'today' | 'schedule' | 'orders' | 'coaches' | 'classes' | 'customer' | 'refunds' | 'users' | 'audit' | 'health'>('today')

const today = ref<Record<string, any>>({})
const schedule = ref<Array<Record<string, any>>>([])
const scheduleDate = ref(localToday())
const orders = ref<Array<Record<string, any>>>([])
const orderDate = ref(localToday())
const orderDetail = ref<Record<string, any> | null>(null)
const slotOps = ref<Record<string, any> | null>(null)
const coachList = ref<Array<Record<string, any>>>([])
const coachForm = ref<Record<string, any> | null>(null)
const coachEditingId = ref<number | null>(null)
const scheduleCoach = ref<Record<string, any> | null>(null)
const scheduleRules = ref<Array<Record<string, any>>>([])
const timeOffForm = ref({ startAt: '', endAt: '', reason: '' })
const classDate = ref(localToday())
const classes = ref<Array<Record<string, any>>>([])
const courts = ref<Array<Record<string, any>>>([])
const classForm = ref<Record<string, any> | null>(null)
const classServices = ref<Array<Record<string, any>>>([])
const classSlots = ref<Array<Record<string, any>>>([])
const classDetail = ref<Record<string, any> | null>(null)
const refunds = ref<Array<Record<string, any>>>([])
const users = ref<Array<Record<string, any>>>([])
const customerPhone = ref('')
const customer = ref<Record<string, any> | null>(null)
const customerCoupons = ref<Array<Record<string, any>>>([])
const couponTemplates = ref<Array<Record<string, any>>>([])
const grantTemplateId = ref<number | ''>('')
const grantCoachId = ref<number | ''>('')
const grantSessions = ref(5)
const auditLogs = ref<Array<Record<string, any>>>([])
const auditDate = ref(localToday())
const health = ref<Record<string, any>>({})
const evalRuns = ref<Array<Record<string, any>>>([])
const handoffDetail = ref<Record<string, any> | null>(null)

const REASON_TEXT: Record<string, string> = {
  USER_REQUEST: '用户要求人工', PAYMENT: '涉及退款 / 支付', COMPLAINT: '投诉',
  OUT_OF_SCOPE: '超出了 AI 能办的范围', REPEATED_FAILURE: 'AI 没听懂，已兜底转人工',
}
const ROLE_TEXT: Record<string, string> = { CUSTOMER: '顾客', COACH: '教练', ADMIN: '管理员' }
const USTATUS_TEXT: Record<string, string> = { ACTIVE: '正常', FROZEN: '已封禁' }
const BSTATUS_TEXT: Record<string, string> = {
  PENDING_PAYMENT: '待支付', CONFIRMED: '已确认', COMPLETED: '已完成', CANCELLED: '已取消', NO_SHOW: '爽约',
}
const REFUND_TEXT: Record<string, string> = { SUCCESS: '已退款', PENDING: '处理中', FAILED: '失败' }
const CHANNEL_TEXT: Record<string, string> = { WALLET: '余额', WECHAT_MOCK: '微信' }
const CELL_TEXT: Record<string, string> = { BOOKED: '已订', HELD: '锁定', CLOSED: '已关', AVAILABLE: '' }
const CLASS_TEXT: Record<string, string> = { OPEN: '报名中', FULL: '已满', CANCELLED: '已取消', COMPLETED: '已完成' }
const ENROLL_TEXT: Record<string, string> = { CONFIRMED: '已报名', WAITLIST: '候补', CANCELLED: '已取消' }
const COACH_STATUS_TEXT: Record<string, string> = { ACTIVE: '在岗', DISABLED: '停用' }
const ACTION_TEXT: Record<string, string> = {
  SLOT_CLOSE: '关场', SLOT_REOPEN: '重新开放', SLOT_PRICE: '单格改价',
  BOOKING_FORCE_CANCEL: '强制取消订单',
  COACH_CREATE: '新增教练', COACH_UPDATE: '编辑教练', COACH_SCHEDULE: '调整排班',
  COACH_TIME_OFF: '教练请假', COACH_TIME_OFF_CANCEL: '撤销请假',
  GROUP_CLASS_CREATE: '开团课', GROUP_CLASS_CANCEL: '取消团课',
  CUSTOMER_GRANT_COUPON: '发券', CUSTOMER_GRANT_PACKAGE: '补课包',
  CUSTOMER_BAN: '封禁客户', CUSTOMER_UNBAN: '解除封禁',
}
const AUDIT_ACTIONS = Object.keys(ACTION_TEXT)
const COUPON_STATUS: Record<string, string> = { UNUSED: '未使用', USED: '已使用', EXPIRED: '已过期' }

async function api<T = any>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const response = await fetch(BASE + path, {
    method: options.method ?? 'GET',
    headers: {
      ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token.value ? { Authorization: `Bearer ${token.value}` } : {}),
    },
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  })
  const payload = await response.json()
  if (payload.code !== 0) throw new Error(payload.message || '请求失败')
  return payload.data as T
}

async function login() {
  error.value = ''
  try {
    const data = await api<{ accessToken: string }>('/auth/login', {
      method: 'POST',
      body: { grantType: 'PASSWORD', phone: phone.value, password: password.value },
    })
    token.value = data.accessToken
    localStorage.setItem('lanjie.admin.token', data.accessToken)
    await openTab('today')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
    token.value = ''
  }
}

function logout() {
  token.value = ''
  localStorage.removeItem('lanjie.admin.token')
}

async function openTab(next: typeof tab.value) {
  tab.value = next
  error.value = ''
  loading.value = true
  try {
    if (next === 'today') today.value = await api('/admin/today')
    if (next === 'schedule') schedule.value = await api(`/admin/schedule?date=${scheduleDate.value}`)
    if (next === 'orders') orders.value = await api(`/admin/orders?date=${orderDate.value}`)
    if (next === 'coaches') coachList.value = await api('/admin/coaches')
    if (next === 'classes') {
      classes.value = await api(`/admin/group-classes?date=${classDate.value}`)
      if (!coachList.value.length) coachList.value = await api('/admin/coaches')
    }
    if (next === 'refunds') refunds.value = await api('/admin/refunds')
    if (next === 'users') users.value = await api('/admin/users')
    if (next === 'audit') auditLogs.value = await api(`/admin/audit-logs?date=${auditDate.value}`)
    if (next === 'health') {
      health.value = await api('/admin/ai/summary')
      evalRuns.value = await api('/admin/ai/evals')
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function checkin(row: Record<string, any>) {
  const input = window.prompt(`核销「${row.userName} · ${row.time} · ${row.title}」\n请输入顾客出示的核销码`)
  if (!input) return
  try {
    await api(`/admin/bookings/${row.bookingId}/checkin`, { method: 'POST', body: { token: input } })
    await openTab('today')
  } catch (e) {
    alert(e instanceof Error ? e.message : '核销失败')
  }
}

async function viewHandoff(row: Record<string, any>) {
  handoffDetail.value = await api(`/admin/ai/handoffs/${row.handoffId}/detail`)
}

function openSlot(row: Record<string, any>, cell: Record<string, any>) {
  if (cell.status === 'BOOKED' && cell.bookingId) {
    viewOrder(cell.bookingId)
    return
  }
  slotOps.value = { ...cell, courtName: row.courtName }
}

async function closeSlot(force = false) {
  if (!slotOps.value) return
  const reason = window.prompt('关场原因（会记录，便于对账）', '场馆原因关场')
  if (reason === null) return
  try {
    const data = await api(`/admin/slots/${slotOps.value.slotId}/close`, {
      method: 'POST',
      body: { force, reason },
    })
    if (data.blocked) {
      const names = (data.affected ?? []).map((a: any) => `${a.userName}（${a.userPhone}）`).join('、')
      if (!window.confirm(`${data.message}\n\n受影响订单：${names}\n确认后将全额退款并关场，确定吗？`)) return
      await api(`/admin/slots/${slotOps.value.slotId}/close`, {
        method: 'POST',
        body: { force: true, reason },
      })
    }
    slotOps.value = null
    await openTab(tab.value)
  } catch (e) {
    alert(e instanceof Error ? e.message : '关场失败')
  }
}

async function reopenSlot() {
  if (!slotOps.value) return
  if (!window.confirm(`开放「${slotOps.value.courtName} ${slotOps.value.time}」给顾客预订？`)) return
  try {
    await api(`/admin/slots/${slotOps.value.slotId}/reopen`, { method: 'POST' })
    slotOps.value = null
    await openTab(tab.value)
  } catch (e) {
    alert(e instanceof Error ? e.message : '开放失败')
  }
}

async function changePrice() {
  if (!slotOps.value) return
  const current = Number(slotOps.value.priceCents) / 100
  const input = window.prompt(`「${slotOps.value.courtName} ${slotOps.value.time}」改价\n当前 ¥${current}，输入新价格（元）`, String(current))
  if (input === null) return
  const yuan = Number(input)
  if (!Number.isFinite(yuan) || yuan <= 0) {
    alert('价格必须是大于 0 的数字')
    return
  }
  try {
    await api(`/admin/slots/${slotOps.value.slotId}/price`, {
      method: 'PATCH',
      body: { priceCents: Math.round(yuan * 100) },
    })
    slotOps.value = null
    await openTab(tab.value)
  } catch (e) {
    alert(e instanceof Error ? e.message : '改价失败')
  }
}

async function viewOrder(id: number) {
  try {
    orderDetail.value = await api(`/admin/orders/${id}`)
  } catch (e) {
    alert(e instanceof Error ? e.message : '订单加载失败')
  }
}

async function forceCancelOrder() {
  if (!orderDetail.value) return
  const booking = orderDetail.value.booking
  const reason = window.prompt('强制取消原因（顾客可见）', '场馆原因取消')
  if (reason === null) return
  if (!window.confirm(`取消订单 ${booking.bookingNo}？\n已实付 ${money(booking.paidCents)} 将全额退回。`)) return
  try {
    const data = await api(`/admin/orders/${booking.id}/force-cancel`, {
      method: 'POST',
      body: { reason },
    })
    alert(`已取消，退款 ${money(data.refundCents)}`)
    orderDetail.value = null
    await openTab(tab.value)
  } catch (e) {
    alert(e instanceof Error ? e.message : '取消失败')
  }
}

function timeRange(row: Record<string, any>): string {
  if (!row.startAt) return '—'
  const start = String(row.startAt).slice(11, 16)
  const end = String(row.endAt).slice(11, 16)
  return `${start} – ${end}`
}

function historyText(row: Record<string, any>): string {
  const from = BSTATUS_TEXT[row.fromStatus] ?? row.fromStatus ?? '创建'
  const to = BSTATUS_TEXT[row.toStatus] ?? row.toStatus
  return from ? `${from} → ${to}` : to
}

function targetText(row: Record<string, any>): string {
  const label: Record<string, string> = {
    SLOT: '时段', BOOKING: '订单', COACH: '教练', GROUP_CLASS: '团课', USER: '客户',
  }
  return `${label[row.targetType] ?? row.targetType ?? ''} #${row.targetId ?? '—'}`
}

// ---- 教练管理 ----

const WEEKDAY_TEXT = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

function openCoachCreate() {
  coachEditingId.value = null
  coachForm.value = {
    displayName: '', phone: '', password: '', specialties: '', bio: '',
    basePriceYuan: 300, commissionPercent: 20, status: 'ACTIVE',
  }
}

function openCoachEdit(row: Record<string, any>) {
  coachEditingId.value = row.id
  coachForm.value = {
    displayName: row.displayName,
    phone: row.phone,
    password: '',
    specialties: row.specialties ?? '',
    bio: row.bio ?? '',
    basePriceYuan: Number(row.basePriceCents) / 100,
    commissionPercent: Math.round(Number(row.commissionRate) * 100),
    status: row.status,
  }
}

async function saveCoach() {
  const form = coachForm.value
  if (!form) return
  if (!String(form.displayName).trim()) {
    alert('请填教练姓名')
    return
  }
  if (!coachEditingId.value && (!String(form.phone).trim() || String(form.password).length < 6)) {
    alert('新教练需要手机号和至少 6 位初始密码')
    return
  }
  const body: Record<string, any> = {
    displayName: form.displayName,
    specialties: form.specialties,
    bio: form.bio,
    basePriceCents: Math.round(Number(form.basePriceYuan) * 100),
    commissionRate: Number(form.commissionPercent) / 100,
  }
  try {
    if (coachEditingId.value) {
      body.status = form.status
      await api(`/admin/coaches/${coachEditingId.value}`, { method: 'PUT', body })
    } else {
      body.phone = form.phone
      body.password = form.password
      await api('/admin/coaches', { method: 'POST', body })
    }
    coachForm.value = null
    await openTab('coaches')
  } catch (e) {
    alert(e instanceof Error ? e.message : '保存失败')
  }
}

async function openSchedule(row: Record<string, any>) {
  try {
    const data = await api(`/admin/coaches/${row.id}/schedule`)
    const byDay = new Map<number, Record<string, any>>(
      (data.rules ?? []).map((r: Record<string, any>) => [Number(r.weekday), r]),
    )
    scheduleRules.value = [1, 2, 3, 4, 5, 6, 7].map((weekday) => {
      const rule = byDay.get(weekday)
      return {
        weekday,
        enabled: !!rule,
        start: rule ? String(rule.startTime).slice(0, 5) : '09:00',
        end: rule ? String(rule.endTime).slice(0, 5) : '22:00',
      }
    })
    timeOffForm.value = { startAt: '', endAt: '', reason: '' }
    scheduleCoach.value = { ...row, upcoming: data.upcoming ?? [], timeOffs: data.timeOffs ?? [] }
  } catch (e) {
    alert(e instanceof Error ? e.message : '排班加载失败')
  }
}

async function saveSchedule() {
  if (!scheduleCoach.value) return
  const rules = scheduleRules.value
    .filter((r) => r.enabled)
    .map((r) => ({ weekday: r.weekday, startTime: r.start, endTime: r.end }))
  try {
    await api(`/admin/coaches/${scheduleCoach.value.id}/schedule`, { method: 'PUT', body: { rules } })
    await openSchedule(scheduleCoach.value)
    alert('排班已保存，未来 30 天档期已按新规则重建')
  } catch (e) {
    alert(e instanceof Error ? e.message : '排班保存失败')
  }
}

async function submitTimeOff(force = false) {
  if (!scheduleCoach.value) return
  const form = timeOffForm.value
  if (!form.startAt || !form.endAt) {
    alert('选一下请假的开始和结束时间')
    return
  }
  try {
    const data = await api(`/admin/coaches/${scheduleCoach.value.id}/time-off`, {
      method: 'POST',
      body: { startAt: form.startAt, endAt: form.endAt, reason: form.reason, force },
    })
    if (data.blocked) {
      const names = (data.affected ?? [])
        .map((a: Record<string, any>) => `${a.userName}（${String(a.startAt).slice(5, 16).replace('T', ' ')}）`)
        .join('、')
      if (!window.confirm(`${data.message}\n\n受影响：${names}\n确认全额退款/退课时并请假？`)) return
      return submitTimeOff(true)
    }
    alert(`请假已登记，关闭 ${data.closedSlots} 个时段` +
      (data.cancelled?.length ? `，取消 ${data.cancelled.length} 节课并按场馆原因全退` : ''))
    timeOffForm.value = { startAt: '', endAt: '', reason: '' }
    await openSchedule(scheduleCoach.value)
    await openTab('coaches')
  } catch (e) {
    alert(e instanceof Error ? e.message : '请假失败')
  }
}

async function removeTimeOff(off: Record<string, any>) {
  if (!scheduleCoach.value) return
  if (!window.confirm('撤销这次请假并重新开放时段？')) return
  try {
    await api(`/admin/coaches/${scheduleCoach.value.id}/time-offs/${off.id}`, { method: 'DELETE' })
    await openSchedule(scheduleCoach.value)
  } catch (e) {
    alert(e instanceof Error ? e.message : '撤销失败')
  }
}

// ---- 团课管理 ----

async function openClassCreate() {
  if (!courts.value.length) {
    courts.value = await api('/courts')
  }
  classForm.value = {
    coachId: coachList.value[0]?.id ?? '',
    serviceId: '',
    date: classDate.value,
    slotId: '',
    courtId: courts.value[0]?.id ?? '',
  }
  await onClassCoachChange()
}

async function onClassCoachChange() {
  const form = classForm.value
  if (!form) return
  form.serviceId = ''
  form.slotId = ''
  classServices.value = []
  classSlots.value = []
  if (!form.coachId) return
  const services = await api(`/admin/coaches/${form.coachId}/services`)
  classServices.value = services.filter((s: Record<string, any>) => s.type === 'GROUP')
  if (classServices.value.length) form.serviceId = classServices.value[0].id
  await loadClassSlots()
}

async function loadClassSlots() {
  const form = classForm.value
  if (!form) return
  classSlots.value = []
  form.slotId = ''
  if (!form.coachId || !form.date) return
  classSlots.value = await api(`/admin/coaches/${form.coachId}/slots?date=${form.date}`)
  if (classSlots.value.length) form.slotId = classSlots.value[0].id
}

async function saveClass() {
  const form = classForm.value
  if (!form) return
  const slot = classSlots.value.find((s) => s.id === form.slotId)
  if (!slot) {
    alert('没有可用的教练时段，换一天或先调整排班')
    return
  }
  if (!form.serviceId || !form.courtId) {
    alert('选好团课服务和场地')
    return
  }
  try {
    await api('/admin/group-classes', {
      method: 'POST',
      body: {
        coachId: Number(form.coachId),
        serviceId: Number(form.serviceId),
        startAt: slot.startAt,
        courtId: Number(form.courtId),
      },
    })
    classForm.value = null
    await openTab('classes')
  } catch (e) {
    alert(e instanceof Error ? e.message : '开课失败')
  }
}

async function openClassDetail(row: Record<string, any>) {
  try {
    classDetail.value = await api(`/admin/group-classes/${row.id}`)
  } catch (e) {
    alert(e instanceof Error ? e.message : '名单加载失败')
  }
}

async function cancelClass() {
  if (!classDetail.value) return
  const clazz = classDetail.value.groupClass
  const reason = window.prompt('取消团课的原因（已报名顾客会看到）', '场馆原因取消团课')
  if (reason === null) return
  if (!window.confirm(`取消「${clazz.title}」？\n已报名顾客将全额退款，候补一并取消。`)) return
  try {
    await api(`/admin/group-classes/${clazz.id}/cancel`, { method: 'POST', body: { reason } })
    classDetail.value = null
    await openTab('classes')
  } catch (e) {
    alert(e instanceof Error ? e.message : '取消失败')
  }
}

async function resolveHandoff(row: Record<string, any>) {
  await api(`/admin/ai/handoffs/${row.handoffId}/resolve`, { method: 'POST' })
  handoffDetail.value = null
  await openTab('today')
}

async function searchCustomer() {
  if (!customerPhone.value.trim()) return
  const data = await api(`/admin/customer?phone=${encodeURIComponent(customerPhone.value.trim())}`)
  if (!data?.userName) {
    customer.value = null
    customerCoupons.value = []
    return
  }
  customer.value = data
  customerCoupons.value = await api(`/admin/customers/${data.userId}/coupons`)
  if (!couponTemplates.value.length) couponTemplates.value = await api('/admin/coupons/templates')
  if (!coachList.value.length) coachList.value = await api('/admin/coaches')
  grantTemplateId.value = couponTemplates.value[0]?.id ?? ''
  grantCoachId.value = coachList.value[0]?.id ?? ''
  grantSessions.value = 5
}

async function toggleCustomerBan() {
  const current = customer.value
  if (!current) return
  const unban = current.status === 'FROZEN'
  if (!window.confirm(unban ? '解除封禁，恢复订场？' : '封禁 7 天？封禁期间不能订场。')) return
  try {
    await api(`/admin/users/${current.userId}/ban`, {
      method: 'POST',
      body: { unban, days: unban ? 0 : 7, reason: unban ? '' : '前台手动封禁' },
    })
    await searchCustomer()
  } catch (e) {
    alert(e instanceof Error ? e.message : '操作失败')
  }
}

async function grantCoupon() {
  if (!customer.value || !grantTemplateId.value) {
    alert('先选一张券')
    return
  }
  try {
    await api('/admin/coupons/grant', {
      method: 'POST',
      body: { userId: customer.value.userId, templateId: Number(grantTemplateId.value) },
    })
    customerCoupons.value = await api(`/admin/customers/${customer.value.userId}/coupons`)
    alert('已发券')
  } catch (e) {
    alert(e instanceof Error ? e.message : '发券失败')
  }
}

async function grantPackage() {
  if (!customer.value || !grantCoachId.value) {
    alert('先选教练')
    return
  }
  try {
    const services = await api(`/admin/coaches/${grantCoachId.value}/services`)
    const service = services.find((s: Record<string, any>) => s.type === 'PRIVATE') ?? services[0]
    if (!service) {
      alert('该教练还没有可用服务')
      return
    }
    await api(`/admin/customers/${customer.value.userId}/packages`, {
      method: 'POST',
      body: {
        coachId: Number(grantCoachId.value),
        serviceId: service.id,
        sessions: Number(grantSessions.value),
        reason: '前台补偿',
      },
    })
    await searchCustomer()
    alert(`已补 ${grantSessions.value} 节课`)
  } catch (e) {
    alert(e instanceof Error ? e.message : '补课包失败')
  }
}

async function toggleBan(row: Record<string, any>) {
  const unban = row.status === 'FROZEN'
  await api(`/admin/users/${row.id}/ban`, {
    method: 'POST',
    body: { unban, days: unban ? 0 : 7, reason: unban ? '' : '管理员封禁 7 天' },
  })
  await openTab('users')
}

async function runEval() {
  try {
    await api('/admin/ai/evals/run', { method: 'POST' })
    await openTab('health')
  } catch (e) {
    alert(e instanceof Error ? e.message : '评测失败')
  }
}

function money(cents: unknown): string {
  return `¥${Number(cents ?? 0) / 100}`
}

/** 本地时区的今天，避免 UTC 导致凌晨显示成昨天 */
function localToday(): string {
  const now = new Date()
  now.setMinutes(now.getMinutes() - now.getTimezoneOffset())
  return now.toISOString().slice(0, 10)
}

function relative(iso: unknown): string {
  const t = new Date(String(iso)).getTime()
  if (Number.isNaN(t)) return ''
  const diff = Math.floor((Date.now() - t) / 60000)
  if (diff < 1) return '刚刚'
  if (diff < 60) return `${diff} 分钟前`
  if (diff < 24 * 60) return `${Math.floor(diff / 60)} 小时前`
  return `${Math.floor(diff / 1440)} 天前`
}

onMounted(() => {
  if (token.value) openTab('today')
})
</script>

<template>
  <div v-if="!token" class="login-wrap">
    <CourtBackdrop />
    <div class="login-card">
      <div class="login-brand"><i></i>LANJIE · 篮界</div>
      <h1>前台<em>工作台</em></h1>
      <p>排期 · 核销 · 退款 · 会员 · AI 质量</p>
      <div class="field"><span>手机号</span><input v-model="phone" /></div>
      <div class="field"><span>密码</span><input v-model="password" type="password" @keyup.enter="login" /></div>
      <p v-if="error" class="err">{{ error }}</p>
      <button class="btn" :disabled="loading" @click="login">进入管理台</button>
    </div>
  </div>

  <div v-else class="shell">
    <aside class="side">
      <h2>篮界管理台</h2>
      <button :class="{ active: tab === 'today' }" @click="openTab('today')">前台工作台</button>
      <button :class="{ active: tab === 'schedule' }" @click="openTab('schedule')">今日排期</button>
      <button :class="{ active: tab === 'orders' }" @click="openTab('orders')">订单处置</button>
      <button :class="{ active: tab === 'coaches' }" @click="openTab('coaches')">教练管理</button>
      <button :class="{ active: tab === 'classes' }" @click="openTab('classes')">团课管理</button>
      <button :class="{ active: tab === 'customer' }" @click="openTab('customer')">客户查询</button>
      <button :class="{ active: tab === 'refunds' }" @click="openTab('refunds')">退款记录</button>
      <button :class="{ active: tab === 'users' }" @click="openTab('users')">用户管理</button>
      <button :class="{ active: tab === 'audit' }" @click="openTab('audit')">操作审计</button>
      <button :class="{ active: tab === 'health' }" @click="openTab('health')">系统健康</button>
      <button style="margin-top: 18px; color: #8fa294" @click="logout">退出登录</button>
    </aside>

    <main class="main">
      <p v-if="error" class="err">{{ error }}</p>

      <template v-if="tab === 'today' && today.summary">
        <h3>前台工作台</h3>
        <div class="cards">
          <div class="stat"><small>今天进账</small><b>{{ money(today.summary.revenueCents) }}</b></div>
          <div class="stat"><small>待核销</small><b>{{ today.summary.checkinCount }}</b></div>
          <div class="stat"><small>待处理（AI 转人工）</small><b>{{ today.summary.handoffCount }}</b></div>
          <div class="stat"><small>场地占用</small><b>{{ today.summary.occupancyPct }}%</b></div>
        </div>
        <p class="alert-line" v-if="today.summary.noShows || today.summary.overduePending">
          <span v-if="today.summary.noShows">今天 {{ today.summary.noShows }} 人爽约；</span>
          <span v-if="today.summary.overduePending">{{ today.summary.overduePending }} 单超时未支付已自动取消。</span>
        </p>

        <div class="queue">
          <h4>待核销 <small>客人到了，核销码对一下</small></h4>
          <div v-for="row in today.checkinQueue" :key="row.bookingId" class="qrow">
            <div class="qmain">
              <b>{{ row.time }} · {{ row.userName }}</b>
              <small>{{ row.title }} · {{ row.userPhone }}</small>
            </div>
            <button class="btn sm" @click="checkin(row)">核销</button>
          </div>
          <p v-if="!today.checkinQueue.length" class="empty">现在没有待核销的客人</p>
        </div>

        <div class="queue">
          <h4>待处理 <small>AI 办不了的事，轮到人了</small></h4>
          <div v-for="row in today.handoffQueue" :key="row.handoffId" class="qrow">
            <div class="qmain">
              <b>{{ row.userName }}：{{ row.lastWords }}</b>
              <small>{{ REASON_TEXT[row.reason] ?? row.reason }} · {{ relative(row.createdAt) }}</small>
            </div>
            <div class="qactions">
              <button class="btn sm ghost" @click="viewHandoff(row)">看对话</button>
              <button class="btn sm" @click="resolveHandoff(row)">已处理</button>
            </div>
          </div>
          <p v-if="!today.handoffQueue.length" class="empty">AI 今天没把难题甩过来</p>
        </div>

        <div class="queue" v-if="today.refundQueue?.length">
          <h4>待退款 <small>系统自动退失败才需要你</small></h4>
          <div v-for="row in today.refundQueue" :key="row.id" class="qrow">
            <div class="qmain"><b>{{ money(row.amountCents) }}</b><small>{{ row.reason }} · {{ REFUND_TEXT[row.status] ?? row.status }}</small></div>
          </div>
        </div>
      </template>

      <template v-if="tab === 'schedule'">
        <h3>今日排期</h3>
        <div class="toolbar">
          <input type="date" v-model="scheduleDate" @change="openTab('schedule')" />
          <span class="legend"><i class="lg avail"></i>可订<i class="lg booked"></i>已订<i class="lg held"></i>锁定<i class="lg off"></i>已关</span>
        </div>
        <p class="hint-line">点格子就能关场、开放、改价；已订格子点开看订单。</p>
        <table class="grid-table">
          <thead><tr><th>场地</th><th v-for="cell in schedule[0]?.cells ?? []" :key="cell.time">{{ cell.time }}</th></tr></thead>
          <tbody>
            <tr v-for="row in schedule" :key="row.courtName">
              <td class="court-cell">{{ row.courtName }}</td>
              <td v-for="cell in row.cells" :key="cell.time">
                <div class="slot-cell" :class="[String(cell.status).toLowerCase(), { clickable: cell.status !== 'HELD' }]"
                     :title="cell.status === 'BOOKED' ? `查看 ${cell.userName} 的订单` : '点击操作'"
                     @click="cell.status !== 'HELD' && openSlot(row, cell)">
                  <b v-if="cell.status === 'BOOKED'">{{ cell.userName }}</b>
                  <span v-else>{{ CELL_TEXT[cell.status] ?? '' }}</span>
                  <small v-if="cell.status === 'AVAILABLE'">{{ money(cell.priceCents) }}</small>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </template>

      <template v-if="tab === 'orders'">
        <h3>订单处置</h3>
        <div class="toolbar">
          <input type="date" v-model="orderDate" @change="openTab('orders')" />
          <span class="hint-inline">当天场地订单，点「查看」可强制取消并全额退款</span>
        </div>
        <table>
          <thead><tr><th>时间</th><th>顾客</th><th>内容</th><th>状态</th><th>实付</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in orders" :key="row.id">
              <td>{{ timeRange(row) }}</td>
              <td>{{ row.userName }}<br /><small>{{ row.userPhone }}</small></td>
              <td>{{ row.title }}</td>
              <td><span class="chip" :class="row.status === 'CONFIRMED' ? 'ok' : row.status === 'CANCELLED' ? 'bad' : 'warn'">{{ BSTATUS_TEXT[row.status] ?? row.status }}</span></td>
              <td>{{ money(row.paidCents) }}</td>
              <td><button class="btn sm ghost" @click="viewOrder(row.id)">查看</button></td>
            </tr>
          </tbody>
        </table>
        <p v-if="!orders.length" class="empty">这天没有场地订单</p>
      </template>

      <template v-if="tab === 'coaches'">
        <h3>教练管理</h3>
        <div class="toolbar">
          <span class="hint-inline">新教练入职后默认全天可约（09:00–22:00），可在「排班/请假」里调整；停用后不可再被预订。</span>
          <button class="btn" @click="openCoachCreate">新增教练</button>
        </div>
        <table>
          <thead><tr><th>教练</th><th>手机号</th><th>专长</th><th>课时价</th><th>抽成</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="c in coachList" :key="c.id">
              <td>{{ c.displayName }}</td>
              <td>{{ c.phone }}</td>
              <td>{{ c.specialties }}</td>
              <td>{{ money(c.basePriceCents) }}</td>
              <td>{{ Math.round(Number(c.commissionRate) * 100) }}%</td>
              <td><span class="chip" :class="c.status === 'ACTIVE' ? 'ok' : 'bad'">{{ COACH_STATUS_TEXT[c.status] ?? c.status }}</span></td>
              <td class="ops-row-inline">
                <button class="btn sm ghost" @click="openCoachEdit(c)">编辑</button>
                <button class="btn sm ghost" @click="openSchedule(c)">排班 / 请假</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="!coachList.length" class="empty">还没有教练</p>
      </template>

      <template v-if="tab === 'classes'">
        <h3>团课管理</h3>
        <div class="toolbar">
          <input type="date" v-model="classDate" @change="openTab('classes')" />
          <button class="btn" @click="openClassCreate">新建团课</button>
          <span class="hint-inline">4 人开班、上限 8 人；开课前按教练空闲档期排，场地自动占用。</span>
        </div>
        <table>
          <thead><tr><th>时间</th><th>团课</th><th>教练</th><th>报名</th><th>单价</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="c in classes" :key="c.id">
              <td>{{ String(c.startAt).slice(11, 16) }} – {{ String(c.endAt).slice(11, 16) }}</td>
              <td>{{ c.title }}</td>
              <td>{{ c.coachName }}</td>
              <td>{{ c.enrolledCount }}/{{ c.maxStudents }}<small v-if="c.enrolledCount < c.minStudents"> · 未成班</small></td>
              <td>{{ money(c.priceCents) }}</td>
              <td><span class="chip" :class="c.status === 'OPEN' ? 'ok' : c.status === 'CANCELLED' ? 'bad' : 'warn'">{{ CLASS_TEXT[c.status] ?? c.status }}</span></td>
              <td><button class="btn sm ghost" @click="openClassDetail(c)">名单 / 取消</button></td>
            </tr>
          </tbody>
        </table>
        <p v-if="!classes.length" class="empty">这天没有团课</p>
      </template>

      <template v-if="tab === 'customer'">
        <h3>客户查询</h3>
        <div class="toolbar">
          <input v-model="customerPhone" placeholder="输入手机号，例如 13900000001" style="width: 260px" @keyup.enter="searchCustomer" />
          <button class="btn" @click="searchCustomer">查一下</button>
        </div>
        <template v-if="customer">
          <div class="cards">
            <div class="stat"><small>顾客</small><b>{{ customer.userName }}</b></div>
            <div class="stat"><small>钱包余额</small><b>{{ money(customer.walletCents) }}</b></div>
            <div class="stat"><small>身份</small><b>{{ ROLE_TEXT[customer.role] ?? customer.role }}</b></div>
            <div class="stat"><small>课包</small><b>{{ customer.packages?.length ?? 0 }} 个</b></div>
          </div>

          <div class="action-panel">
            <div class="action-title">
              <b>客户运营</b>
              <span v-if="customer.status === 'FROZEN'" class="chip bad">
                已封禁<template v-if="customer.bannedUntil">至 {{ String(customer.bannedUntil).slice(5, 10) }}</template>
              </span>
            </div>
            <div class="action-grid">
              <button class="btn sm ghost" @click="toggleCustomerBan">
                {{ customer.status === 'FROZEN' ? '解除封禁' : '封禁 7 天' }}
              </button>
              <div class="inline-form">
                <select v-model.number="grantTemplateId">
                  <option v-for="t in couponTemplates" :key="t.id" :value="t.id">{{ t.name }}</option>
                </select>
                <button class="btn sm ghost" @click="grantCoupon">发券</button>
              </div>
              <div class="inline-form">
                <select v-model.number="grantCoachId">
                  <option v-for="c in coachList.filter((x) => x.status === 'ACTIVE')" :key="c.id" :value="c.id">{{ c.displayName }}</option>
                </select>
                <input v-model.number="grantSessions" type="number" min="1" max="50" style="width: 64px" />
                <button class="btn sm ghost" @click="grantPackage">补课包</button>
              </div>
            </div>
          </div>

          <h4>课包</h4>
          <table>
            <thead><tr><th>剩余课时</th><th>总课时</th><th>到期时间</th><th>购课金额</th></tr></thead>
            <tbody>
              <tr v-for="p in customer.packages" :key="p.id">
                <td>{{ p.remainingSessions }} 节</td>
                <td>{{ p.totalSessions }} 节</td>
                <td>{{ String(p.expiresAt).slice(0, 10) }}</td>
                <td>{{ money(p.priceCents) }}</td>
              </tr>
            </tbody>
          </table>
          <p v-if="!customer.packages?.length" class="empty">还没有课包</p>

          <h4>优惠券</h4>
          <table>
            <thead><tr><th>券</th><th>状态</th><th>到期时间</th></tr></thead>
            <tbody>
              <tr v-for="c in customerCoupons" :key="c.id">
                <td>{{ c.name }}</td>
                <td>{{ COUPON_STATUS[c.status] ?? c.status }}</td>
                <td>{{ c.expiresAt ? String(c.expiresAt).slice(0, 10) : '—' }}</td>
              </tr>
            </tbody>
          </table>
          <p v-if="!customerCoupons.length" class="empty">还没有券</p>

          <h4>最近订单</h4>
          <table>
            <thead><tr><th>内容</th><th>状态</th><th>金额</th></tr></thead>
            <tbody>
              <tr v-for="b in customer.bookings" :key="b.bookingId">
                <td>{{ b.title }}</td>
                <td>{{ BSTATUS_TEXT[b.status] ?? b.status }}</td>
                <td>{{ money(b.paidCents) }}</td>
              </tr>
            </tbody>
          </table>
          <p v-if="!customer.bookings?.length" class="empty">还没下过单</p>
        </template>
        <p v-else-if="customerPhone && !loading" class="empty">没找到这个手机号的顾客</p>
      </template>

      <template v-if="tab === 'refunds'">
        <h3>退款记录</h3>
        <table>
          <thead><tr><th>时间</th><th>金额</th><th>方式</th><th>原因</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="row in refunds" :key="row.id">
              <td>{{ String(row.createdAt).slice(5, 16).replace('T', ' ') }}</td>
              <td>{{ money(row.amountCents) }}</td>
              <td>{{ CHANNEL_TEXT[row.channel] ?? row.channel }}</td>
              <td>{{ row.reason }}</td>
              <td><span class="chip" :class="row.status === 'SUCCESS' ? 'ok' : 'warn'">{{ REFUND_TEXT[row.status] ?? row.status }}</span></td>
            </tr>
          </tbody>
        </table>
        <p v-if="!refunds.length" class="empty">还没有退款记录</p>
      </template>

      <template v-if="tab === 'users'">
        <h3>用户管理</h3>
        <table>
          <thead><tr><th>顾客</th><th>手机号</th><th>身份</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in users" :key="row.id">
              <td>{{ row.nickname }}</td>
              <td>{{ row.phone }}</td>
              <td>{{ ROLE_TEXT[row.role] ?? row.role }}</td>
              <td><span class="chip" :class="row.status === 'ACTIVE' ? 'ok' : 'bad'">{{ USTATUS_TEXT[row.status] ?? row.status }}</span></td>
              <td><button class="btn sm ghost" @click="toggleBan(row)">{{ row.status === 'FROZEN' ? '解封' : '封禁 7 天' }}</button></td>
            </tr>
          </tbody>
        </table>
      </template>

      <template v-if="tab === 'audit'">
        <h3>操作审计</h3>
        <div class="toolbar">
          <input type="date" v-model="auditDate" @change="openTab('audit')" />
          <span class="hint-inline">关场、改价、退款、教练排班、发券、封禁等敏感操作都会留痕。</span>
        </div>
        <table>
          <thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>对象</th><th>详情</th></tr></thead>
          <tbody>
            <tr v-for="row in auditLogs" :key="row.id">
              <td>{{ String(row.createdAt).slice(5, 16).replace('T', ' ') }}</td>
              <td>{{ row.actorName }}</td>
              <td>{{ ACTION_TEXT[row.action] ?? row.action }}</td>
              <td>{{ targetText(row) }}</td>
              <td>{{ row.detail }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="!auditLogs.length" class="empty">这天还没有敏感操作</p>
      </template>

      <template v-if="tab === 'health'">
        <h3>系统健康</h3>
        <div class="cards">
          <div class="stat"><small>今天 AI 接待</small><b>{{ health.sessionsToday ?? 0 }} 人</b></div>
          <div class="stat"><small>转人工</small><b>{{ health.handoffsToday ?? 0 }} 人</b></div>
          <div class="stat"><small>AI 评测通过率</small><b>{{ health.evalTotal ? Math.round((health.evalPassed / health.evalTotal) * 100) : 0 }}%</b></div>
          <div class="stat"><small>最近评测</small><b>{{ health.evalAt ? String(health.evalAt).slice(5, 16).replace('T', ' ') : '未跑过' }}</b></div>
        </div>
        <p class="hint-line">这页给技术/负责人看；前台日常只看「今日工作台」就够了。</p>
        <div class="toolbar">
          <button class="btn" :disabled="loading" @click="runEval">重跑 40 条评测</button>
        </div>
        <h4>最近答得不好的问题</h4>
        <table>
          <thead><tr><th>用户怎么问</th><th>AI 怎么答</th></tr></thead>
          <tbody>
            <tr v-for="(c, i) in health.failedCases ?? []" :key="i">
              <td>{{ c.question }}</td>
              <td>{{ c.answer }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="!health.failedCases?.length" class="empty">最近一次评测没有失败用例</p>
        <h4>历次评测</h4>
        <table>
          <thead><tr><th>时间</th><th>通过</th><th>失败</th></tr></thead>
          <tbody>
            <tr v-for="row in evalRuns" :key="row.id">
              <td>{{ String(row.startedAt).slice(5, 16).replace('T', ' ') }}</td>
              <td>{{ row.passed }}/{{ row.total }}</td>
              <td>{{ row.failed }}</td>
            </tr>
          </tbody>
        </table>
      </template>
    </main>

    <div v-if="coachForm" class="modal-mask" @click.self="coachForm = null">
      <div class="modal">
        <div class="modal-head">
          <b>{{ coachEditingId ? '编辑教练' : '新增教练' }}</b>
          <button class="modal-close" @click="coachForm = null">✕</button>
        </div>
        <div class="form-grid">
          <label>姓名<input v-model="coachForm.displayName" /></label>
          <label v-if="!coachEditingId">手机号<input v-model="coachForm.phone" placeholder="登录账号" /></label>
          <label v-if="!coachEditingId">初始密码<input v-model="coachForm.password" type="password" placeholder="至少 6 位" /></label>
          <label>课时价（元）<input v-model.number="coachForm.basePriceYuan" type="number" min="1" /></label>
          <label>平台抽成（%）<input v-model.number="coachForm.commissionPercent" type="number" min="0" max="90" /></label>
          <label v-if="coachEditingId">状态
            <select v-model="coachForm.status">
              <option value="ACTIVE">在岗</option>
              <option value="DISABLED">停用</option>
            </select>
          </label>
          <label class="span2">专长<input v-model="coachForm.specialties" placeholder="例如：投篮专项 · 青少年" /></label>
          <label class="span2">简介<textarea v-model="coachForm.bio" rows="2"></textarea></label>
        </div>
        <p class="hint-line">课时价同时用于私教单节；调价不影响已售订单。新教练默认全天可约，可在排班里改。</p>
        <div class="ops-row">
          <button class="btn sm ghost" @click="coachForm = null">取消</button>
          <button class="btn sm" @click="saveCoach">保存</button>
        </div>
      </div>
    </div>

    <div v-if="scheduleCoach" class="modal-mask" @click.self="scheduleCoach = null">
      <div class="modal modal-lg">
        <div class="modal-head">
          <b>{{ scheduleCoach.displayName }} · 排班与请假</b>
          <button class="modal-close" @click="scheduleCoach = null">✕</button>
        </div>

        <h4>每周可约时间</h4>
        <div v-for="r in scheduleRules" :key="r.weekday" class="weekday-row">
          <label class="check"><input v-model="r.enabled" type="checkbox" />{{ WEEKDAY_TEXT[r.weekday - 1] }}</label>
          <input v-model="r.start" type="time" :disabled="!r.enabled" />
          <span>至</span>
          <input v-model="r.end" type="time" :disabled="!r.enabled" />
        </div>
        <div class="ops-row">
          <button class="btn sm" @click="saveSchedule">保存排班并重建未来档期</button>
        </div>
        <p class="hint-line">只重建未售出的档期；已售课程不受影响，改完如需闭店请用请假。</p>

        <h4>代请假</h4>
        <div class="form-grid">
          <label>开始<input v-model="timeOffForm.startAt" type="datetime-local" /></label>
          <label>结束<input v-model="timeOffForm.endAt" type="datetime-local" /></label>
          <label class="span2">原因<input v-model="timeOffForm.reason" placeholder="例如：外出培训 / 临时停课" /></label>
        </div>
        <div class="ops-row">
          <button class="btn sm" @click="submitTimeOff(false)">登记请假</button>
        </div>
        <p class="hint-line">区间内已售课程会先列出来，确认后按场馆原因全额退款或退回课时。</p>

        <template v-if="scheduleCoach.timeOffs?.length">
          <h4>已登记的请假</h4>
          <div class="timeline">
            <div v-for="off in scheduleCoach.timeOffs" :key="off.id" class="timeline-row">
              <span>{{ String(off.startAt).slice(5, 16).replace('T', ' ') }}</span>
              <b>至 {{ String(off.endAt).slice(5, 16).replace('T', ' ') }}</b>
              <small>
                {{ off.reason }}
                <button class="btn sm ghost" @click="removeTimeOff(off)">撤销并重开</button>
              </small>
            </div>
          </div>
        </template>

        <h4>未来 7 天档期</h4>
        <table>
          <thead><tr><th>时间</th><th>状态</th><th>顾客</th></tr></thead>
          <tbody>
            <tr v-for="s in scheduleCoach.upcoming?.slice(0, 30)" :key="s.id">
              <td>{{ String(s.startAt).slice(5, 16).replace('T', ' ') }}</td>
              <td>{{ s.status === 'AVAILABLE' ? '可约' : s.status === 'BOOKED' ? '已售' : s.status === 'CLOSED' ? '关闭' : s.status }}</td>
              <td>{{ s.userName || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="classForm" class="modal-mask" @click.self="classForm = null">
      <div class="modal">
        <div class="modal-head">
          <b>新建团课</b>
          <button class="modal-close" @click="classForm = null">✕</button>
        </div>
        <div class="form-grid">
          <label>教练
            <select v-model.number="classForm.coachId" @change="onClassCoachChange">
              <option value="">选教练</option>
              <option v-for="c in coachList.filter((x) => x.status === 'ACTIVE')" :key="c.id" :value="c.id">{{ c.displayName }}</option>
            </select>
          </label>
          <label>团课服务
            <select v-model.number="classForm.serviceId">
              <option value="">选服务</option>
              <option v-for="s in classServices" :key="s.id" :value="s.id">
                {{ s.title }} · {{ money(s.priceCents) }}/人 · {{ s.minStudents }}–{{ s.maxStudents }} 人
              </option>
            </select>
          </label>
          <label>日期<input v-model="classForm.date" type="date" @change="loadClassSlots" /></label>
          <label>教练空闲时段
            <select v-model.number="classForm.slotId">
              <option value="">选时段</option>
              <option v-for="s in classSlots" :key="s.id" :value="s.id">
                {{ String(s.startAt).slice(11, 16) }} – {{ String(s.endAt).slice(11, 16) }}
              </option>
            </select>
          </label>
          <label>场地
            <select v-model.number="classForm.courtId">
              <option v-for="c in courts" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
          </label>
        </div>
        <p class="hint-line">只能选教练真正空闲的时段；场地冲突会直接拒绝，不会超卖。</p>
        <div class="ops-row">
          <button class="btn sm ghost" @click="classForm = null">取消</button>
          <button class="btn sm" @click="saveClass">开班</button>
        </div>
      </div>
    </div>

    <div v-if="classDetail" class="modal-mask" @click.self="classDetail = null">
      <div class="modal">
        <div class="modal-head">
          <b>{{ classDetail.groupClass.title }}</b>
          <button class="modal-close" @click="classDetail = null">✕</button>
        </div>
        <div class="detail-grid">
          <div><small>教练</small><b>{{ classDetail.coachName }}</b></div>
          <div><small>时间</small><b>{{ String(classDetail.groupClass.startAt).slice(5, 16).replace('T', ' ') }} – {{ String(classDetail.groupClass.endAt).slice(11, 16) }}</b></div>
          <div><small>报名</small><b>{{ classDetail.groupClass.enrolledCount }}/{{ classDetail.groupClass.maxStudents }}（{{ classDetail.groupClass.minStudents }} 人开班）</b></div>
          <div><small>单价</small><b>{{ money(classDetail.groupClass.priceCents) }}</b></div>
          <div><small>状态</small><b>{{ CLASS_TEXT[classDetail.groupClass.status] ?? classDetail.groupClass.status }}</b></div>
        </div>

        <h4>报名名单</h4>
        <table>
          <thead><tr><th>顾客</th><th>手机号</th><th>报名状态</th><th>支付</th></tr></thead>
          <tbody>
            <tr v-for="r in classDetail.roster" :key="r.enrollmentId">
              <td>{{ r.userName }}</td>
              <td>{{ r.userPhone }}</td>
              <td>
                <span class="chip" :class="r.status === 'CONFIRMED' ? 'ok' : r.status === 'WAITLIST' ? 'warn' : 'bad'">
                  {{ ENROLL_TEXT[r.status] ?? r.status }}
                </span>
              </td>
              <td>{{ BSTATUS_TEXT[r.bookingStatus] ?? r.bookingStatus }} · {{ money(r.paidCents) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="!classDetail.roster?.length" class="empty">还没有人报名</p>

        <div class="ops-row" v-if="['OPEN', 'FULL'].includes(classDetail.groupClass.status)">
          <button class="btn sm danger" @click="cancelClass">取消整班并全员退款</button>
        </div>
      </div>
    </div>

    <div v-if="slotOps" class="modal-mask" @click.self="slotOps = null">
      <div class="modal modal-sm">
        <div class="modal-head">
          <b>{{ slotOps.courtName }} · {{ slotOps.time }}</b>
          <button class="modal-close" @click="slotOps = null">✕</button>
        </div>
        <p class="detail-line">
          当前状态：
          <span class="chip" :class="slotOps.status === 'AVAILABLE' ? 'ok' : 'warn'">
            {{ slotOps.status === 'AVAILABLE' ? `可订 · ${money(slotOps.priceCents)}` : '已关场' }}
          </span>
        </p>
        <div class="ops-row" v-if="slotOps.status === 'AVAILABLE'">
          <button class="btn sm ghost" @click="changePrice">改价</button>
          <button class="btn sm" @click="closeSlot(false)">关场</button>
        </div>
        <div class="ops-row" v-else>
          <button class="btn sm" @click="reopenSlot">重新开放</button>
        </div>
        <p class="hint-line">关场时若已有顾客预订，会先列出受影响订单，确认后全额退款并取消。</p>
      </div>
    </div>

    <div v-if="orderDetail" class="modal-mask" @click.self="orderDetail = null">
      <div class="modal">
        <div class="modal-head">
          <b>订单 {{ orderDetail.booking.bookingNo }}</b>
          <button class="modal-close" @click="orderDetail = null">✕</button>
        </div>
        <div class="detail-grid">
          <div><small>顾客</small><b>{{ orderDetail.userName }}</b></div>
          <div><small>手机号</small><b>{{ orderDetail.userPhone }}</b></div>
          <div><small>状态</small><b>{{ BSTATUS_TEXT[orderDetail.booking.status] ?? orderDetail.booking.status }}</b></div>
          <div><small>实付 / 应付</small><b>{{ money(orderDetail.booking.paidCents) }} / {{ money(orderDetail.booking.payableCents) }}</b></div>
        </div>

        <h4>场次明细</h4>
        <table>
          <thead><tr><th>内容</th><th>时间</th><th>金额</th></tr></thead>
          <tbody>
            <tr v-for="item in orderDetail.items" :key="item.id">
              <td>{{ item.title }}</td>
              <td>{{ String(item.startAt).slice(5, 16).replace('T', ' ') }} – {{ String(item.endAt).slice(11, 16) }}</td>
              <td>{{ money(item.amountCents) }}</td>
            </tr>
          </tbody>
        </table>

        <h4>状态流转</h4>
        <div class="timeline">
          <div v-for="h in orderDetail.history" :key="h.id" class="timeline-row">
            <span>{{ String(h.createdAt).slice(5, 16).replace('T', ' ') }}</span>
            <b>{{ historyText(h) }}</b>
            <small>{{ h.actor === 'ADMIN' ? '前台' : h.actor === 'USER' ? '顾客' : h.actor }} · {{ h.reason }}</small>
          </div>
          <p v-if="!orderDetail.history?.length" class="empty">暂无流转记录</p>
        </div>

        <h4>支付 / 退款</h4>
        <div class="timeline">
          <div v-for="p in orderDetail.pays" :key="'p' + p.id" class="timeline-row">
            <span>{{ String(p.paidAt ?? p.createdAt).slice(5, 16).replace('T', ' ') }}</span>
            <b>支付 {{ money(p.amountCents) }}</b>
            <small>{{ CHANNEL_TEXT[p.channel] ?? p.channel }} · {{ p.status }}</small>
          </div>
          <div v-for="r in orderDetail.refunds" :key="'r' + r.id" class="timeline-row">
            <span>{{ String(r.createdAt).slice(5, 16).replace('T', ' ') }}</span>
            <b>退款 {{ money(r.amountCents) }}</b>
            <small>{{ r.reason }} · {{ REFUND_TEXT[r.status] ?? r.status }}</small>
          </div>
          <p v-if="!orderDetail.pays?.length && !orderDetail.refunds?.length" class="empty">还没有支付记录</p>
        </div>

        <div class="ops-row" v-if="orderDetail.booking.type === 'COURT' && ['PENDING_PAYMENT', 'CONFIRMED'].includes(orderDetail.booking.status)">
          <button class="btn sm danger" @click="forceCancelOrder">
            {{ orderDetail.booking.status === 'CONFIRMED' ? '强制取消并全额退款' : '取消未支付订单' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="handoffDetail" class="modal-mask" @click.self="handoffDetail = null">
      <div class="modal">
        <div class="modal-head">
          <b>{{ handoffDetail.userName }} · {{ handoffDetail.userPhone }}</b>
          <button class="modal-close" @click="handoffDetail = null">✕</button>
        </div>
        <div class="chat-trace">
          <div v-for="(m, i) in handoffDetail.messages" :key="i" class="trace-row" :class="String(m.role).toLowerCase()">
            <span>{{ m.role === 'USER' ? '顾客' : 'AI' }}</span>
            <p>{{ m.content }}</p>
          </div>
        </div>
        <button class="btn" @click="resolveHandoff({ handoffId: handoffDetail.handoff.id })">标记已处理</button>
      </div>
    </div>
  </div>
</template>
