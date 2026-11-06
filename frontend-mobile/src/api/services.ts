import { request } from './http'
import type { BookingInfo, HoldInfo } from './bookings'

const uuid = () =>
  typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`

// ---- 教练 ----
export interface CoachServiceInfo {
  id: number
  type: string
  title: string
  priceCents: number
  includesCourt: boolean
  minStudents: number
  maxStudents: number
}

export interface CoachInfo {
  id: number
  displayName: string
  bio: string
  specialties: string
  rating: number
  ratingCount: number
  basePriceCents: number
  services: CoachServiceInfo[]
}

export interface CoachSlotInfo {
  id: number
  startAt: string
  endAt: string
  status: string
}

export interface CoachPackageInfo {
  id: number
  coachName: string
  serviceTitle: string
  totalSessions: number
  usedSessions: number
  remainingSessions: number
  priceCents: number
  expiresAt: string
  status: string
}

export const fetchCoaches = () => request<CoachInfo[]>('/coaches', { auth: true })
export const fetchCoachSlots = (coachId: number, date: string) =>
  request<CoachSlotInfo[]>(`/coaches/${coachId}/slots?date=${date}`, { auth: true })
export const fetchMyPackages = () => request<CoachPackageInfo[]>('/me/packages', { auth: true })

export function holdCoach(payload: {
  coachSlotId: number
  serviceId: number
  courtSlotId?: number
  packageId?: number
}): Promise<HoldInfo> {
  return request<HoldInfo>('/coach-bookings/holds', {
    method: 'POST',
    body: payload,
    auth: true,
    idempotencyKey: uuid(),
  })
}

export function buyPackage(coachId: number, sessions: number): Promise<{ payNo: string }> {
  return request('/coach-packages', {
    method: 'POST',
    body: { coachId, sessions, channel: 'WECHAT_MOCK' },
    auth: true,
    idempotencyKey: uuid(),
  })
}

// ---- 拼场 ----
export interface PickupParticipant {
  userId: number
  nickname: string
  status: string
}

export interface PickupGameInfo {
  id: number
  slotId: number
  courtName: string
  startAt: string
  endAt: string
  format: string
  totalPlayers: number
  joinedPlayers: number
  pricePerPersonCents: number
  note: string
  status: string
  settled: boolean
  isInitator: boolean
  participants: PickupParticipant[]
}

export const fetchPickups = (date: string, format?: string) =>
  request<PickupGameInfo[]>(`/pickup-games?date=${date}${format ? `&format=${format}` : ''}`, { auth: true })

export function createPickup(slotId: number, format: string, note: string): Promise<HoldInfo> {
  return request<HoldInfo>('/pickup-games', {
    method: 'POST',
    body: { slotId, format, note },
    auth: true,
    idempotencyKey: uuid(),
  })
}

export function joinPickup(gameId: number): Promise<HoldInfo> {
  return request<HoldInfo>(`/pickup-games/${gameId}/join`, {
    method: 'POST',
    body: {},
    auth: true,
    idempotencyKey: uuid(),
  })
}

// ---- 会员与 AI ----
export interface MemberInfo {
  levelName: string
  discountPct: number
  pointsRate: number
  cumulativeRechargeCents: number
  pointsBalance: number
}

export const fetchMember = () => request<MemberInfo>('/me/member', { auth: true })

export interface AiStep {
  type: string
  tool: string
  status: string
  latencyMs: number
  result: string
}

export interface AiChatResult {
  sessionId: number
  answer: string
  steps: AiStep[]
  handoff: boolean
}

export function createAiSession(): Promise<{ sessionId: number; model: string }> {
  return request('/ai/sessions', { method: 'POST', body: {}, auth: true })
}

export function sendAiMessage(sessionId: number, content: string): Promise<AiChatResult> {
  return request<AiChatResult>(`/ai/sessions/${sessionId}/messages`, {
    method: 'POST',
    body: { content },
    auth: true,
  })
}

export function confirmAiAction(token: string): Promise<{ bookingId: number }> {
  return request(`/ai/confirmations/${token}`, { method: 'POST', body: {}, auth: true })
}

export function requestHandoff(sessionId: number): Promise<void> {
  return request<void>(`/ai/sessions/${sessionId}/handoff`, {
    method: 'POST',
    body: { reason: 'USER_REQUEST', summary: '用户点按钮转人工' },
    auth: true,
  })
}

export type { BookingInfo, HoldInfo }
