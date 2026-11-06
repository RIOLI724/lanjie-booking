<script setup lang="ts">
import { onMounted, ref } from 'vue'
import CourtBackdrop from './components/CourtBackdrop.vue'

const BASE = '/api/v1'
const token = ref(localStorage.getItem('lanjie.admin.token') ?? '')
const phone = ref('13800000000')
const password = ref('admin123')
const error = ref('')
const loading = ref(false)
const tab = ref<'today' | 'schedule' | 'customer' | 'refunds' | 'users' | 'health'>('today')

const today = ref<Record<string, any>>({})
const schedule = ref<Array<Record<string, any>>>([])
const scheduleDate = ref(new Date().toISOString().slice(0, 10))
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
      <button :class="{ active: tab === 'today' }" @click="openTab('today')">今日工作台</button>
      <button :class="{ active: tab === 'schedule' }" @click="openTab('schedule')">今日排期</button>
      <button :class="{ active: tab === 'customer' }" @click="openTab('customer')">客户查询</button>
      <button :class="{ active: tab === 'refunds' }" @click="openTab('refunds')">退款记录</button>
      <button :class="{ active: tab === 'users' }" @click="openTab('users')">用户管理</button>
      <button :class="{ active: tab === 'health' }" @click="openTab('health')">系统健康</button>
      <button style="margin-top: 18px; color: #8fa294" @click="logout">退出登录</button>
    </aside>

    <main class="main">
      <p v-if="error" class="err">{{ error }}</p>

      <template v-if="tab === 'today' && today.summary">
        <h3>王姐，今天要做这些事</h3>
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
        <table class="grid-table">
          <thead><tr><th>场地</th><th v-for="cell in schedule[0]?.cells ?? []" :key="cell.time">{{ cell.time }}</th></tr></thead>
          <tbody>
            <tr v-for="row in schedule" :key="row.courtName">
              <td class="court-cell">{{ row.courtName }}</td>
              <td v-for="cell in row.cells" :key="cell.time">
                <div class="slot-cell" :class="String(cell.status).toLowerCase()">
                  <b v-if="cell.status === 'BOOKED'">{{ cell.userName }}</b>
                  <span v-else>{{ CELL_TEXT[cell.status] ?? '' }}</span>
                  <small v-if="cell.status === 'AVAILABLE'">{{ money(cell.priceCents) }}</small>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
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
