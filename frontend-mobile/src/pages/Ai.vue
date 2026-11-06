<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DockBar from '@/components/DockBar.vue'
import {
  confirmAiAction, createAiSession, requestHandoff, sendAiMessage, type AiStep,
} from '@/api/services'
import { ApiError } from '@/api/http'

interface Bubble {
  role: 'user' | 'assistant'
  text: string
  steps?: AiStep[]
  handoff?: boolean
}

const sessionId = ref(0)
const messages = ref<Bubble[]>([])
const input = ref('')
const busy = ref(false)
const error = ref('')
const confirmTokens = ref<Record<number, string>>({})

async function send() {
  const text = input.value.trim()
  if (!text || busy.value || !sessionId.value) return
  messages.value.push({ role: 'user', text })
  input.value = ''
  busy.value = true
  error.value = ''
  try {
    const result = await sendAiMessage(sessionId.value, text)
    messages.value.push({ role: 'assistant', text: result.answer, steps: result.steps, handoff: result.handoff })
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
    messages.value.push({ role: 'assistant', text: '已为你锁位 15 分钟，去「我的订单」完成支付。' })
    delete confirmTokens.value[index]
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '确认失败'
  }
}

async function handoff() {
  if (!sessionId.value) return
  await requestHandoff(sessionId.value).catch(() => {})
  messages.value.push({ role: 'assistant', text: '已转前台，请稍候。' })
}

onMounted(async () => {
  const session = await createAiSession()
  sessionId.value = session.sessionId
  messages.value.push({
    role: 'assistant',
    text: '你好，我是篮界 AI 前台。订场、约教练、问规则都可以，涉及付款和退款我会转前台。',
  })
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">AI 前台</h1>

    <div class="chat-list">
      <div v-for="(m, i) in messages" :key="i" class="chat-row" :class="m.role">
        <div class="bubble">
          <div v-if="m.steps?.length" class="steps">
            <div v-for="(s, j) in m.steps" :key="j" class="step">
              <span>{{ s.tool }}</span><small>{{ s.status }} · {{ s.latencyMs }}ms</small>
            </div>
          </div>
          <div>{{ m.text }}</div>
          <button v-if="confirmTokens[i] && !m.handoff" class="btn-ghost" style="margin-top: 8px" @click="confirm(i)">
            确认锁位
          </button>
        </div>
      </div>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <div class="chat-input">
      <input v-model="input" placeholder="例如：明晚想打球，有场吗" @keyup.enter="send" />
      <button class="btn-ghost" @click="handoff">转人工</button>
      <button class="btn-primary" style="width: auto; padding: 0 16px" :disabled="busy || !input.trim()" @click="send">
        {{ busy ? '…' : '发送' }}
      </button>
    </div>

    <DockBar />
  </div>
</template>
