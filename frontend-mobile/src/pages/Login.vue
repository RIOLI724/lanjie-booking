<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { sendSmsCode } from '@/api/auth'
import { ApiError } from '@/api/http'
import { loginByPassword, loginBySms } from '@/stores/session'
import CourtBackdrop from '@/components/CourtBackdrop.vue'

const route = useRoute()
const router = useRouter()

const mode = ref<'PASSWORD' | 'SMS'>('PASSWORD')
const phone = ref('')
const password = ref('')
const showPassword = ref(false)
const code = ref('')
const error = ref('')
const submitting = ref(false)

const phoneValid = computed(() => /^1\d{10}$/.test(phone.value))
const canSubmit = computed(() => {
  if (!phoneValid.value || submitting.value) return false
  return mode.value === 'PASSWORD' ? password.value.length >= 6 : code.value.length >= 4
})

async function submit() {
  error.value = ''
  submitting.value = true
  try {
    if (mode.value === 'PASSWORD') {
      await loginByPassword(phone.value, password.value)
    } else {
      await loginBySms(phone.value, code.value)
    }
    router.push((route.query.redirect as string) || '/')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    submitting.value = false
  }
}

const countdown = ref(0)
let timer: number | undefined

async function sendCode() {
  if (!phoneValid.value || countdown.value > 0) return
  error.value = ''
  try {
    await sendSmsCode(phone.value, 'LOGIN')
    countdown.value = 60
    timer = window.setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0 && timer) window.clearInterval(timer)
    }, 1000)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  }
}

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="page-auth">
    <div class="auth-visual">
      <CourtBackdrop />
      <div class="auth-visual-shade"></div>
      <div class="brand">
        <h1>篮<span>界</span></h1>
        <small>城东体育馆 · 4 片全场 · AI 前台</small>
      </div>
    </div>

    <div class="auth-form">
      <div class="seg">
      <button :class="{ active: mode === 'PASSWORD' }" @click="mode = 'PASSWORD'">密码登录</button>
      <button :class="{ active: mode === 'SMS' }" @click="mode = 'SMS'">验证码登录</button>
    </div>

    <label class="field">
      <span>手机号</span>
      <input v-model="phone" inputmode="numeric" maxlength="11" placeholder="11 位手机号" />
    </label>

    <label v-if="mode === 'PASSWORD'" class="field">
      <span>密码</span>
      <div class="control">
        <input v-model="password" :type="showPassword ? 'text' : 'password'" maxlength="32" placeholder="6-32 位" />
        <button type="button" class="pwd-toggle" :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword">
          <svg v-if="!showPassword" viewBox="0 0 24 24" width="18" height="18" fill="none"
               stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12z" />
            <circle cx="12" cy="12" r="3" />
          </svg>
          <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none"
               stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 3l18 18" />
            <path d="M10.6 6.1A10.8 10.8 0 0112 6c6.5 0 10 6 10 6a17.8 17.8 0 01-3.2 3.9" />
            <path d="M6.7 6.7A17.6 17.6 0 002 12s3.5 6 10 6a10.7 10.7 0 004.1-.8" />
            <path d="M9.9 9.9a3 3 0 004.2 4.2" />
          </svg>
        </button>
      </div>
    </label>

    <label v-else class="field">
      <span>验证码</span>
      <div class="control">
        <input v-model="code" inputmode="numeric" maxlength="6" placeholder="6 位验证码" />
        <button class="btn-ghost" :disabled="!phoneValid || countdown > 0" @click="sendCode">
          {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
        </button>
      </div>
    </label>

    <p v-if="error" class="error">{{ error }}</p>

    <button class="btn-primary" :disabled="!canSubmit" @click="submit">
      {{ submitting ? '登录中…' : '登录' }}
    </button>

    <p class="hint">
      还没有账号？<router-link to="/register">手机号注册</router-link>
    </p>
    </div>
  </div>
</template>
