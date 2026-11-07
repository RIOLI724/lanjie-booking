<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  confirmAiAction, createAiSession, requestHandoff, sendAiMessage, type AiStep,
} from '@/api/services'
import { ApiError } from '@/api/http'
import { ensureLucide, renderIcons } from '@/utils/icons'

interface Bubble {
  role: 'user' | 'assistant'
  text: string
  steps?: AiStep[]
  handoff?: boolean
  time: string
}

const router = useRouter()
const sessionId = ref(0)
const messages = ref<Bubble[]>([])
const input = ref('')
const busy = ref(false)
const error = ref('')
const confirmTokens = ref<Record<number, string>>({})
const chatEl = ref<HTMLElement | null>(null)

function nowLabel(): string {
  const d = new Date()
  return `${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function scrollToBottom() {
  nextTick(() => {
    if (chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight
  })
}

async function send() {
  const text = input.value.trim()
  if (!text || busy.value || !sessionId.value) return
  messages.value.push({ role: 'user', text, time: nowLabel() })
  input.value = ''
  busy.value = true
  error.value = ''
  scrollToBottom()
  try {
    const result = await sendAiMessage(sessionId.value, text)
    messages.value.push({
      role: 'assistant', text: result.answer, steps: result.steps,
      handoff: result.handoff, time: nowLabel(),
    })
    for (const step of result.steps) {
      try {
        const parsed = JSON.parse(step.result)
        if (parsed.proposal && parsed.token) {
          confirmTokens.value[messages.value.length - 1] = parsed.token
        }
      } catch {
        // 非 JSON 结果忽略
      }
    }
    scrollToBottom()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}

async function confirm(index: number) {
  const token = confirmTokens.value[index]
  if (!token) return
  try {
    await confirmAiAction(token)
    messages.value.push({
      role: 'assistant', text: '已为你锁位 15 分钟，去「我的订单」完成支付。', time: nowLabel(),
    })
    delete confirmTokens.value[index]
    scrollToBottom()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '确认失败'
  }
}

async function handoff() {
  if (!sessionId.value) return
  await requestHandoff(sessionId.value).catch(() => {})
  messages.value.push({ role: 'assistant', text: '已转前台，请稍候。', handoff: true, time: nowLabel() })
  scrollToBottom()
}

watch(busy, () => scrollToBottom())

onMounted(async () => {
  try {
    const session = await createAiSession()
    sessionId.value = session.sessionId
    messages.value.push({
      role: 'assistant',
      text: '晚上好，我是篮界前台。订场、约教练、拼场，直接说就行。',
      time: nowLabel(),
    })
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : 'AI 前台连接失败'
  }
  await nextTick()
  try {
    await ensureLucide()
    renderIcons(16)
  } catch {
    // ignore
  }
  scrollToBottom()
})
</script>

<template>
  <div id="lanjie-proto" class="demo-ai">
    <div class="phone-screen">
      <div class="ai-head">
        <button class="icon-btn" aria-label="返回" @click="router.push('/')">
          <i data-lucide="arrow-left"></i>
        </button>
        <span class="ai-avatar"><i data-lucide="sparkles"></i></span>
        <div class="ai-id"><b>AI 前台</b><small>在线 · 平均 1.8 秒</small></div>
      </div>

      <div ref="chatEl" class="chat">
        <div class="chat-date">今天 {{ nowLabel().slice(-5) }}</div>
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div v-if="m.steps?.length" class="tool-card">
            <div v-for="(s, j) in m.steps" :key="j" class="tool-row">
              <i data-lucide="check"></i>
              <span>{{ s.tool }}</span>
              <em>{{ s.status === 'OK' ? '完成' : s.status }} · {{ s.latencyMs }}ms</em>
            </div>
          </div>
          <div class="bubble">{{ m.text }}</div>
          <button v-if="confirmTokens[i] && !m.handoff" class="quick-reply" style="margin-top: 6px" @click="confirm(i)">
            确认锁位
          </button>
        </div>

        <div v-if="error" class="error">{{ error }}</div>

        <div class="quick-replies">
          <button class="quick-reply" @click="handoff">转人工</button>
          <button class="quick-reply" @click="input = '今晚还有空场吗'">今晚有空场吗</button>
        </div>
      </div>

      <div class="chat-input">
        <button class="ci-btn" aria-label="更多"><i data-lucide="plus"></i></button>
        <input v-model="input" type="text" placeholder="问空场、约教练、拼场…" aria-label="给 AI 前台发消息"
               @keyup.enter="send" />
        <button class="ci-send" aria-label="发送" :disabled="busy || !input.trim()" @click="send">
          <i data-lucide="arrow-up"></i>
        </button>
      </div>
    </div>
  </div>
</template>
