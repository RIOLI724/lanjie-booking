<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import CourtBackdrop from './components/CourtBackdrop.vue'

const BASE = '/api/v1'
const token = ref(localStorage.getItem('lanjie.admin.token') ?? '')
const phone = ref('13800000000')
const password = ref('admin123')
const error = ref('')
const tab = ref<'dashboard' | 'bookings' | 'refunds' | 'users' | 'handoffs' | 'ai'>('dashboard')
const loading = ref(false)

const dashboard = ref<Record<string, number>>({})
const bookings = ref<Record<string, unknown>[]>([])
const refunds = ref<Record<string, unknown>[]>([])
const users = ref<Record<string, unknown>[]>([])
const handoffs = ref<Record<string, unknown>[]>([])
const sessions = ref<Record<string, unknown>[]>([])
const evalRuns = ref<Record<string, unknown>[]>([])

async function api<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
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
    await loadAll()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
    token.value = ''
  }
}

function logout() {
  token.value = ''
  localStorage.removeItem('lanjie.admin.token')
}

async function loadAll() {
  loading.value = true
  try {
    dashboard.value = await api('/admin/dashboard')
    bookings.value = await api('/admin/bookings')
    refunds.value = await api('/admin/refunds')
    users.value = await api('/admin/users')
    handoffs.value = await api('/admin/ai/handoffs')
    sessions.value = await api('/admin/ai/sessions')
    evalRuns.value = await api('/admin/ai/evals')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function checkin(row: Record<string, unknown>) {
  const input = window.prompt('请扫描/输入用户核销码')
  if (!input) return
  try {
    await api(`/admin/bookings/${row.id}/checkin`, { method: 'POST', body: { token: input } })
    row.checkedIn = true
    alert('核销成功')
  } catch (e) {
    alert(e instanceof Error ? e.message : '核销失败')
  }
}

async function toggleBan(row: Record<string, unknown>) {
  const unban = row.status === 'FROZEN'
  try {
    await api(`/admin/users/${row.id}/ban`, {
      method: 'POST',
      body: { unban, days: unban ? 0 : 7, reason: unban ? '' : '管理员封禁 7 天' },
    })
    await loadAll()
  } catch (e) {
    alert(e instanceof Error ? e.message : '操作失败')
  }
}

async function resolveHandoff(row: Record<string, unknown>) {
  await api(`/admin/ai/handoffs/${row.id}/resolve`, { method: 'POST' })
  await loadAll()
}

async function runEval() {
  try {
    const run = await api<Record<string, unknown>>('/admin/ai/evals/run', { method: 'POST' })
    alert(`评测完成：${run.passed}/${run.total}`)
    await loadAll()
  } catch (e) {
    alert(e instanceof Error ? e.message : '评测失败')
  }
}

const statusText = computed<Record<string, string>>(() => ({
  PENDING_PAYMENT: '待支付',
  CONFIRMED: '已确认',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  NO_SHOW: '爽约',
}))

function statusClass(status: string): string {
  if (status === 'CONFIRMED' || status === 'COMPLETED') return 'ok'
  if (status === 'PENDING_PAYMENT') return 'warn'
  return 'bad'
}

function money(cents: unknown): string {
  return `¥${Number(cents ?? 0) / 100}`
}

onMounted(() => {
  if (token.value) loadAll()
})
</script>

<template>
  <div v-if="!token" class="login-wrap">
    <CourtBackdrop />
    <div class="login-card">
      <h1>篮界 <em>·</em> 管理台</h1>
      <p>前台排期 · 核销 · 退款 · 用户 · AI 观测</p>
      <div class="field"><span>手机号</span><input v-model="phone" /></div>
      <div class="field"><span>密码</span><input v-model="password" type="password" @keyup.enter="login" /></div>
      <p v-if="error" class="err">{{ error }}</p>
      <button class="btn" :disabled="loading" @click="login">登录</button>
    </div>
  </div>

  <div v-else class="shell">
    <aside class="side">
      <h2>篮界管理台</h2>
      <button :class="{ active: tab === 'dashboard' }" @click="tab = 'dashboard'">今日看板</button>
      <button :class="{ active: tab === 'bookings' }" @click="tab = 'bookings'">预约与核销</button>
      <button :class="{ active: tab === 'refunds' }" @click="tab = 'refunds'">退款记录</button>
      <button :class="{ active: tab === 'users' }" @click="tab = 'users'">用户管理</button>
      <button :class="{ active: tab === 'handoffs' }" @click="tab = 'handoffs'">AI 工单</button>
      <button :class="{ active: tab === 'ai' }" @click="tab = 'ai'">AI 观测</button>
      <button style="margin-top: 18px; color: #8fa294" @click="logout">退出登录</button>
    </aside>

    <main class="main">
      <p v-if="error" class="err">{{ error }}</p>

      <template v-if="tab === 'dashboard'">
        <h3>今日看板</h3>
        <div class="cards">
          <div class="stat"><small>今日订单</small><b>{{ dashboard.todayOrders ?? 0 }}</b></div>
          <div class="stat"><small>今日营收</small><b>{{ money(dashboard.todayRevenueCents) }}</b></div>
          <div class="stat"><small>场地利用率</small><b>{{ dashboard.occupancyPct ?? 0 }}%</b></div>
          <div class="stat"><small>爽约</small><b>{{ dashboard.noShows ?? 0 }}</b></div>
        </div>
        <div class="cards">
          <div class="stat"><small>已确认</small><b>{{ dashboard.confirmed ?? 0 }}</b></div>
          <div class="stat"><small>已取消</small><b>{{ dashboard.cancelled ?? 0 }}</b></div>
          <div class="stat"><small>今日格子</small><b>{{ dashboard.totalSlots ?? 0 }}</b></div>
          <div class="stat"><small>已售格子</small><b>{{ dashboard.bookedSlots ?? 0 }}</b></div>
        </div>
      </template>

      <template v-if="tab === 'bookings'">
        <h3>预约与核销</h3>
        <table>
          <thead><tr><th>订单号</th><th>类型</th><th>场次</th><th>用户</th><th>金额</th><th>状态</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in bookings" :key="String(row.id)">
              <td>{{ row.bookingNo }}</td>
              <td>{{ row.type }}</td>
              <td>{{ row.title }}</td>
              <td>{{ row.nickname }} {{ row.phone }}</td>
              <td>{{ money(row.paidCents || row.payableCents) }}</td>
              <td><span class="chip" :class="statusClass(String(row.status))">{{ statusText[String(row.status)] ?? row.status }}</span></td>
              <td>
                <button v-if="row.status === 'CONFIRMED'" class="btn sm" @click="checkin(row)">
                  {{ row.checkedIn ? '已核销' : '核销' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="!bookings.length" class="empty">暂无订单</p>
      </template>

      <template v-if="tab === 'refunds'">
        <h3>退款记录</h3>
        <table>
          <thead><tr><th>退款单</th><th>金额</th><th>渠道</th><th>原因</th><th>状态</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="row in refunds" :key="String(row.id)">
              <td>{{ row.refundNo }}</td>
              <td>{{ money(row.amountCents) }}</td>
              <td>{{ row.channel }}</td>
              <td>{{ row.reason }}</td>
              <td><span class="chip" :class="row.status === 'SUCCESS' ? 'ok' : 'warn'">{{ row.status }}</span></td>
              <td>{{ String(row.createdAt ?? '').slice(0, 19).replace('T', ' ') }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="!refunds.length" class="empty">暂无退款</p>
      </template>

      <template v-if="tab === 'users'">
        <h3>用户管理</h3>
        <table>
          <thead><tr><th>昵称</th><th>手机号</th><th>角色</th><th>状态</th><th>封禁至</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in users" :key="String(row.id)">
              <td>{{ row.nickname }}</td>
              <td>{{ row.phone }}</td>
              <td>{{ row.role }}</td>
              <td><span class="chip" :class="row.status === 'ACTIVE' ? 'ok' : 'bad'">{{ row.status }}</span></td>
              <td>{{ row.bannedUntil ?? '-' }}</td>
              <td>
                <button class="btn sm ghost" @click="toggleBan(row)">
                  {{ row.status === 'FROZEN' ? '解封' : '封禁 7 天' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </template>

      <template v-if="tab === 'handoffs'">
        <h3>AI 转人工工单</h3>
        <table>
          <thead><tr><th>会话</th><th>原因</th><th>摘要</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="row in handoffs" :key="String(row.id)">
              <td>{{ row.sessionId }}</td>
              <td>{{ row.reason }}</td>
              <td>{{ row.summary }}</td>
              <td><span class="chip" :class="row.status === 'OPEN' ? 'warn' : 'ok'">{{ row.status }}</span></td>
              <td>{{ String(row.createdAt ?? '').slice(0, 19).replace('T', ' ') }}</td>
              <td>
                <button v-if="row.status === 'OPEN'" class="btn sm" @click="resolveHandoff(row)">标记处理</button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="!handoffs.length" class="empty">暂无工单</p>
      </template>

      <template v-if="tab === 'ai'">
        <h3>AI 观测</h3>
        <div style="margin-bottom: 12px">
          <button class="btn" :disabled="loading" @click="runEval">重跑 40 条评测</button>
        </div>
        <table>
          <thead><tr><th>运行</th><th>模型</th><th>通过</th><th>失败</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="row in evalRuns" :key="String(row.id)">
              <td>#{{ row.id }}</td>
              <td>{{ row.model }}</td>
              <td>{{ row.passed }}/{{ row.total }}</td>
              <td>{{ row.failed }}</td>
              <td>{{ String(row.startedAt ?? '').slice(0, 19).replace('T', ' ') }}</td>
            </tr>
          </tbody>
        </table>
        <h3 style="margin-top: 18px">最近会话</h3>
        <table>
          <thead><tr><th>会话</th><th>用户</th><th>模型</th><th>状态</th><th>工具调用</th><th>耗时</th></tr></thead>
          <tbody>
            <tr v-for="row in sessions" :key="String(row.sessionId)">
              <td>#{{ row.sessionId }}</td>
              <td>{{ row.userId }}</td>
              <td>{{ row.model }}</td>
              <td><span class="chip" :class="row.status === 'HANDOFF' ? 'warn' : 'ok'">{{ row.status }}</span></td>
              <td>{{ row.tools }}</td>
              <td>{{ row.latencyMs }}ms</td>
            </tr>
          </tbody>
        </table>
        <p v-if="!sessions.length" class="empty">暂无会话</p>
      </template>
    </main>
  </div>
</template>
