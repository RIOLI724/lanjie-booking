<script setup lang="ts">
import { onMounted, ref } from 'vue'
import CourtBackdrop from './components/CourtBackdrop.vue'

const BASE = '/api/v1'
const token = ref(localStorage.getItem('lanjie.admin.token') ?? '')
const phone = ref('13800000000')
const password = ref('admin123')
const error = ref('')
const loading = ref(false)
const tab = ref<'today' | 'schedule' | 'orders' | 'customer' | 'refunds' | 'users' | 'health'>('today')

const today = ref<Record<string, any>>({})
const schedule = ref<Array<Record<string, any>>>([])
const scheduleDate = ref(new Date().toISOString().slice(0, 10))
const orders = ref<Array<Record<string, any>>>([])
const orderDate = ref(new Date().toISOString().slice(0, 10))
const orderDetail = ref<Record<string, any> | null>(null)
const slotOps = ref<Record<string, any> | null>(null)
const refunds = ref<Array<Record<string, any>>>([])
const users = ref<Array<Record<string, any>>>([])
const customerPhone = ref('')
const customer = ref<Record<string, any> | null>(null)
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
    if (next === 'refunds') refunds.value = await api('/admin/refunds')
    if (next === 'users') users.value = await api('/admin/users')
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

async function resolveHandoff(row: Record<string, any>) {
  await api(`/admin/ai/handoffs/${row.handoffId}/resolve`, { method: 'POST' })
  handoffDetail.value = null
  await openTab('today')
}

async function searchCustomer() {
  if (!customerPhone.value.trim()) return
  customer.value = await api(`/admin/customer?phone=${encodeURIComponent(customerPhone.value.trim())}`)
  if (!customer.value?.userName) customer.value = null
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
      <button :class="{ active: tab === 'customer' }" @click="openTab('customer')">客户查询</button>
      <button :class="{ active: tab === 'refunds' }" @click="openTab('refunds')">退款记录</button>
      <button :class="{ active: tab === 'users' }" @click="openTab('users')">用户管理</button>
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
