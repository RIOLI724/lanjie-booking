import { reactive } from 'vue'
import * as authApi from '@/api/auth'
import { clearTokens, revokeServerSession, setTokens } from '@/api/http'

interface SessionState {
  me: authApi.MeInfo | null
  loaded: boolean
}

export const sessionState = reactive<SessionState>({ me: null, loaded: false })

export async function loadMe(): Promise<void> {
  sessionState.me = await authApi.fetchMe()
  sessionState.loaded = true
}

export async function loginByPassword(phone: string, password: string): Promise<void> {
  const tokens = await authApi.login({ grantType: 'PASSWORD', phone, password })
  setTokens(tokens.accessToken, tokens.refreshToken)
  await loadMe()
}

export async function loginBySms(phone: string, code: string): Promise<void> {
  const tokens = await authApi.login({ grantType: 'SMS', phone, code })
  setTokens(tokens.accessToken, tokens.refreshToken)
  await loadMe()
}

export async function register(payload: {
  phone: string
  code: string
  password?: string
  nickname?: string
}): Promise<void> {
  const tokens = await authApi.register(payload)
  setTokens(tokens.accessToken, tokens.refreshToken)
  await loadMe()
}

export async function logout(): Promise<void> {
  await revokeServerSession()
  clearTokens()
  sessionState.me = null
  sessionState.loaded = false
}
