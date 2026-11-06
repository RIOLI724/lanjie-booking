import { request } from './http'

export type GrantType = 'PASSWORD' | 'SMS'
export type SmsScene = 'REGISTER' | 'LOGIN' | 'RESET_PASSWORD'

export interface LoginTokens {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
}

export interface MeInfo {
  id: number
  nickname: string
  phoneMasked: string
  avatarUrl: string | null
  role: string
}

export function sendSmsCode(phone: string, scene: SmsScene): Promise<void> {
  return request<void>('/auth/sms-code', { method: 'POST', body: { phone, scene } })
}

export function register(payload: {
  phone: string
  code: string
  password?: string
  nickname?: string
}): Promise<LoginTokens> {
  return request<LoginTokens>('/auth/register', { method: 'POST', body: payload })
}

export function login(payload: {
  grantType: GrantType
  phone: string
  password?: string
  code?: string
}): Promise<LoginTokens> {
  return request<LoginTokens>('/auth/login', { method: 'POST', body: payload })
}

export function fetchMe(): Promise<MeInfo> {
  return request<MeInfo>('/me', { auth: true })
}

export function updateMe(payload: { nickname?: string; avatarUrl?: string }): Promise<MeInfo> {
  return request<MeInfo>('/me', { method: 'PATCH', body: payload, auth: true })
}
