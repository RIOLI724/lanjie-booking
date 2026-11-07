<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'

const emit = defineEmits<{ go: [tab: string] }>()
const BASE = '/api/v1'
const token = localStorage.getItem('lanjie.admin.token') ?? ''

const today = ref<Record<string, any>>({})
const schedule = ref<Array<Record<string, any>>>([])
const orders = ref<Array<Record<string, any>>>([])
const refunds = ref<Array<Record<string, any>>>([])
const ai = ref<Record<string, any>>({})
const loading = ref(true)
const error = ref('')
const roleShort = localStorage.getItem('lanjie.admin.role') === 'FRONT_DESK' ? '前' : '店'

const BSTATUS_TEXT: Record<string, string> = {
  PENDING_PAYMENT: '待支付', CONFIRMED: '待到店', COMPLETED: '已核销',
  CANCELLED: '已取消', NO_SHOW: '爽约',
}

const now = new Date()
const dateLabel = `${now.getMonth() + 1} 月 ${now.getDate()} 日 ${['周日', '周一', '周二', '周三', '周四', '周五', '周六'][now.getDay()]}`
const todayIso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`

async function api<T = any>(path: string): Promise<T> {
  const res = await fetch(BASE + path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  const payload = await res.json()
  if (payload.code !== 0) throw new Error(payload.message || '请求失败')
  return payload.data as T
}

const summary = computed(() => today.value.summary ?? {})
const checkinQueue = computed(() => today.value.checkinQueue ?? [])
const handoffQueue = computed(() => today.value.handoffQueue ?? [])
const refundQueue = computed(() => today.value.refundQueue ?? [])

const hours = computed<string[]>(() => schedule.value[0]?.cells?.map((c: any) => c.time) ?? [])
const bookedCells = computed(() =>
  schedule.value.reduce((sum, row) => sum + row.cells.filter((c: any) => c.status === 'BOOKED').length, 0),
)
const heldCells = computed(() =>
  schedule.value.reduce((sum, row) => sum + row.cells.filter((c: any) => c.status === 'HELD').length, 0),
)
const todayRefund = computed(() =>
  refunds.value
    .filter((r) => String(r.createdAt).slice(0, 10) === todayIso && r.status === 'SUCCESS')
    .reduce((sum, r) => sum + Number(r.amountCents ?? 0), 0),
)
const emptyRate = computed(() => Math.max(0, 100 - Number(summary.value.occupancyPct ?? 0)))
const evalRate = computed(() =>
  ai.value.evalTotal ? Math.round((ai.value.evalPassed / ai.value.evalTotal) * 100) : 0,
)

interface Block {
  left: number
  width: number
  cls: string
  label: string
  title: string
}

function statusClass(status: string): { cls: string; text: string } {
  if (status === 'COMPLETED') return { cls: 'done', text: '已核销' }
  if (status === 'PENDING_PAYMENT') return { cls: 'pay', text: '待支付' }
  if (status === 'CANCELLED') return { cls: 'refund', text: '已取消' }
  return { cls: 'soon', text: '待到店' }
}

function orderOf(bookingId: number): Record<string, any> | undefined {
  return orders.value.find((o) => o.id === bookingId)
}

function rowBlocks(row: Record<string, any>): Block[] {
  const cells = row.cells ?? []
  const total = cells.length || 1
  const blocks: Block[] = []
  let i = 0
  while (i < cells.length) {
    const cell = cells[i]
    if (cell.status !== 'BOOKED' && cell.status !== 'HELD') {
      i++
      continue
    }
    const bookingId = cell.bookingId
    const kind = cell.status === 'HELD' ? 'pay' : (orderOf(bookingId)?.status === 'COMPLETED' ? 'done' : 'soon')
    let count = 1
    let j = i + 1
    while (
      j < cells.length &&
      bookingId &&
      cells[j].bookingId === bookingId &&
      cells[j].status === cell.status
    ) {
      count++
      j++
    }
    const label = cell.status === 'HELD'
      ? '待支付'
      : (cell.userName ? `${cell.time} ${cell.userName}` : cell.time)
    blocks.push({
      left: (i / total) * 100,
      width: (count / total) * 100,
      cls: kind,
      label,
      title: `${row.courtName} · ${cell.time}${count > 1 ? '–' + cells[i + count - 1].time : ''} · ${cell.userName ?? ''} · ${kind === 'done' ? '已核销' : kind === 'pay' ? '待支付' : '待到店'}`,
    })
    i = j
  }
  return blocks
}

const recent = computed(() => orders.value.slice(0, 5))

onMounted(async () => {
  try {
    const results = await Promise.allSettled([
      api('/admin/today'),
      api(`/admin/schedule?date=${todayIso}`),
      api(`/admin/orders?date=${todayIso}`),
      api('/admin/refunds'),
      api('/admin/ai/summary'),
    ])
    const value = (index: number) => results[index].status === 'fulfilled' ? (results[index] as PromiseFulfilledResult<any>).value : null
    today.value = value(0) ?? {}
    schedule.value = value(1) ?? []
    orders.value = value(2) ?? []
    refunds.value = value(3) ?? []
    ai.value = value(4) ?? {}
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
  await nextTick()
  ;(window as any).lucide?.createIcons({ attrs: { width: 14, height: 14 } })
  if (!(window as any).lucide) {
    const script = document.createElement('script')
    script.src = '/lucide-1.17.0.js'
    script.onload = () => (window as any).lucide?.createIcons({ attrs: { width: 14, height: 14 } })
    document.head.appendChild(script)
  }
})
</script>

<template>
  <div id="lanjie-proto" class="desk-page">
    <main class="desk-main">
      <div class="desk-top">
        <div>
          <div class="desk-title">今日</div>
          <div class="desk-sub">{{ dateLabel }} · {{ schedule[0]?.cells?.[0]?.time ?? '10:00' }}–22:00 · 城东体育馆</div>
        </div>
        <div class="desk-actions">
          <div class="search"><i data-lucide="search"></i><span>搜订单 / 手机号</span></div>
          <button class="btn-dark" @click="emit('go', 'orders')"><i data-lucide="plus"></i>新建预约</button>
          <span class="avatar">{{ roleShort }}</span>
        </div>
      </div>

      <p v-if="error" class="err">{{ error }}</p>

      <div class="kpis">
        <div class="kpi">
          <div class="label"><i data-lucide="calendar-days"></i>今日场次</div>
          <div class="value">{{ bookedCells }}</div>
          <div class="delta">{{ summary.checkinCount ?? 0 }} 待核销 · {{ bookedCells - (summary.checkinCount ?? 0) }} 待到店</div>
        </div>
        <div class="kpi">
          <div class="label"><i data-lucide="scan-line"></i>待核销</div>
          <div class="value">{{ summary.checkinCount ?? 0 }}</div>
          <div class="delta">{{ checkinQueue[0] ? '下一场 ' + checkinQueue[0].time : '现在没有待核销' }}</div>
        </div>
        <div class="kpi">
          <div class="label"><i data-lucide="wallet"></i>实收</div>
          <div class="value">¥{{ (summary.revenueCents ?? 0) / 100 }}</div>
          <div class="delta up">今日已支付订单</div>
        </div>
        <div class="kpi">
          <div class="label"><i data-lucide="rotate-ccw"></i>退款</div>
          <div class="value">¥{{ todayRefund / 100 }}</div>
          <div class="delta">{{ refundQueue.length }} 笔 · 已原路退回</div>
        </div>
        <div class="kpi">
          <div class="label"><i data-lucide="activity"></i>空场率</div>
          <div class="value">{{ emptyRate }}%</div>
          <div class="delta">{{ summary.bookedSlots ?? 0 }}/{{ summary.totalSlots ?? 0 }} 格已售</div>
        </div>
      </div>

      <div class="desk-grid">
        <section class="panel timeline-panel">
          <div class="panel-head">
            <div class="panel-title"><i data-lucide="calendar-clock"></i>今日场地时间轴</div>
            <button class="panel-action" @click="emit('go', 'schedule')">查看排期 <i data-lucide="chevron-right"></i></button>
          </div>
          <div class="tl-hours" :style="{ '--hours': hours.length }" aria-hidden="true">
            <span></span>
            <span v-for="h in hours" :key="h">{{ Number(h.slice(0, 2)) }}</span>
          </div>
          <div v-for="row in schedule" :key="row.courtName" class="tl-row">
            <span class="tl-label">{{ row.courtName }}</span>
            <div class="tl-track">
              <span v-for="(b, i) in rowBlocks(row)" :key="i" class="blk" :class="b.cls"
                    :style="{ left: b.left + '%', width: b.width + '%' }" :title="b.title">
                {{ b.label }}
              </span>
            </div>
          </div>
          <div class="tl-legend">
            <span><i class="lg-dot done"></i>已核销</span>
            <span><i class="lg-dot soon"></i>待到店</span>
            <span><i class="lg-dot pay"></i>待支付</span>
            <span><i class="lg-dot empty"></i>空闲</span>
          </div>
        </section>

        <aside class="side-col">
          <section class="panel">
            <div class="panel-head">
              <div class="panel-title"><i data-lucide="inbox"></i>待办</div>
              <span class="count">{{ handoffQueue.length + checkinQueue.length + heldCells + refundQueue.length }}</span>
            </div>
            <div class="todo-item">
              <span class="todo-icon"><i data-lucide="clock"></i></span>
              <div><b>{{ heldCells }} 格待支付</b><small>15 分钟后自动释放</small></div>
              <button class="todo-action" @click="emit('go', 'orders')">查看</button>
            </div>
            <div class="todo-item">
              <span class="todo-icon"><i data-lucide="rotate-ccw"></i></span>
              <div><b>{{ refundQueue.length }} 笔退款待处理</b><small>系统退款失败才需要人</small></div>
              <button class="todo-action" @click="emit('go', 'refunds')">处理</button>
            </div>
            <div class="todo-item">
              <span class="todo-icon"><i data-lucide="graduation-cap"></i></span>
              <div><b>{{ checkinQueue.length }} 位客人待核销</b><small>到店出示核销码</small></div>
              <button class="todo-action" @click="emit('go', 'today')">核销</button>
            </div>
            <div class="todo-item">
              <span class="todo-icon"><i data-lucide="alert-circle"></i></span>
              <div><b>{{ handoffQueue.length }} 条 AI 转人工</b><small>退款/投诉等待前台接手</small></div>
              <button class="todo-action" @click="emit('go', 'today')">接手</button>
            </div>
          </section>

          <section class="ai-panel">
            <div class="panel-head">
              <div class="panel-title"><i data-lucide="sparkles"></i>AI 前台今日</div>
              <span class="live">LIVE</span>
            </div>
            <div class="ai-stats">
              <div class="ai-stat"><b>{{ ai.sessionsToday ?? 0 }}</b><small>接待</small></div>
              <div class="ai-stat"><b>{{ ai.lockedToday ?? 0 }}</b><small>锁位</small></div>
              <div class="ai-stat"><b>{{ ai.handoffsToday ?? 0 }}</b><small>转人工</small></div>
            </div>
            <div class="ai-bars" aria-hidden="true">
              <span style="height:35%"></span><span style="height:55%"></span><span style="height:42%"></span>
              <span style="height:70%"></span><span style="height:58%"></span><span style="height:86%" class="hot"></span>
              <span style="height:64%"></span>
            </div>
            <div class="ai-foot"><span>真实会话 · 不含评测流量</span><span>评测 {{ evalRate }}%</span></div>
            <button class="ai-link" @click="emit('go', 'health')">查看观测台 <i data-lucide="arrow-up-right"></i></button>
          </section>
        </aside>
      </div>

      <section class="panel table-panel">
        <div class="panel-head">
          <div class="panel-title"><i data-lucide="clipboard-list"></i>最近预约</div>
          <button class="panel-action" @click="emit('go', 'orders')">全部 <i data-lucide="chevron-right"></i></button>
        </div>
        <div class="table-wrap">
          <table class="data-table">
            <thead>
              <tr><th>时间</th><th>场地</th><th>用户</th><th>金额</th><th>状态</th><th class="table-hide">操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="o in recent" :key="o.id">
                <td>{{ o.startAt ? String(o.startAt).slice(11, 16) + '–' + String(o.endAt).slice(11, 16) : '—' }}</td>
                <td>{{ o.title }}</td>
                <td>{{ o.userName }}</td>
                <td>¥{{ (o.paidCents || o.payableCents) / 100 }}</td>
                <td><span class="status" :class="statusClass(o.status).cls">{{ BSTATUS_TEXT[o.status] ?? o.status }}</span></td>
                <td class="table-hide"><button class="todo-action" @click="emit('go', 'orders')">详情</button></td>
              </tr>
            </tbody>
          </table>
          <p v-if="!recent.length && !loading" class="empty">今天还没有订单</p>
        </div>
      </section>
    </main>
  </div>
</template>
