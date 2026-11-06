<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { sendSmsCode } from '@/api/auth'
import { ApiError } from '@/api/http'
import { register } from '@/stores/session'
import CourtBackdrop from '@/components/CourtBackdrop.vue'

const router = useRouter()

const phone = ref('')
const code = ref('')
const nickname = ref('')
const password = ref('')
const error = ref('')
const submitting = ref(false)

const phoneValid = computed(() => /^1\d{10}$/.test(phone.value))
const canSubmit = computed(() => phoneValid.value && code.value.length >= 4 && !submitting.value)

async function submit() {
  error.value = ''
  submitting.value = true
  try {
    await register({
      phone: phone.value,
      code: code.value,
      password: password.value || undefined,
      nickname: nickname.value || undefined,
    })
    router.push('/')
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
    await sendSmsCode(phone.value, 'REGISTER')
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
    <CourtBackdrop />
    <div class="auth-visual-shade"></div>
    <div class="auth-card">
      <div class="brand">
        <h1>注册<span>篮界</span></h1>
        <small>30 秒加入球局 · 4 片全场</small>
      </div>

      <label class="field">
        <span>手机号</span>
        <input v-model="phone" inputmode="numeric" maxlength="11" placeholder="11 位手机号" />
      </label>

    <label class="field">
      <span>验证码</span>
      <div class="control">
        <input v-model="code" inputmode="numeric" maxlength="6" placeholder="6 位验证码" />
        <button class="btn-ghost" :disabled="!phoneValid || countdown > 0" @click="sendCode">
          {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
        </button>
      </div>
    </label>

    <label class="field">
      <span>昵称（选填）</span>
      <input v-model="nickname" maxlength="20" placeholder="默认为「球友+尾号」" />
    </label>

    <label class="field">
      <span>密码（选填，用于密码登录）</span>
      <input v-model="password" type="password" maxlength="32" placeholder="6-32 位" />
    </label>

    <p v-if="error" class="error">{{ error }}</p>

    <button class="btn-primary" :disabled="!canSubmit" @click="submit">
      {{ submitting ? '注册中…' : '注册并登录' }}
    </button>

    <p class="hint">
      已有账号？<router-link to="/login">直接登录</router-link>
    </p>
    </div>
  </div>
</template>
