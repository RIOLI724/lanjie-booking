import { request } from './http'

export interface ItemInfo {
  title: string
  startAt: string
  endAt: string
  priceCents: number
}

export interface BookingInfo {
  id: number
  bookingNo: string
  type: string
  status: string
  listAmountCents: number
  payableCents: number
  paidCents: number
  holdExpiresAt: string | null
  createdAt: string
  rescheduleCount: number
  refundQuoteCents: number
  items: ItemInfo[]
}

export interface HoldInfo {
  bookingId: number
  bookingNo: string
  payableCents: number
  holdExpiresAt: string
  items: ItemInfo[]
}

export interface PayInfo {
  payNo: string
  amountCents: number
  channel: string
}

const uuid = () =>
  typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`

export function holdSlots(slotIds: number[]): Promise<HoldInfo> {
  return request<HoldInfo>('/bookings/holds', {
    method: 'POST',
    body: { slotIds },
    auth: true,
    idempotencyKey: uuid(),
  })
}

export function fetchBookings(status?: string): Promise<BookingInfo[]> {
  return request<BookingInfo[]>(`/bookings${status ? `?status=${status}` : ''}`, { auth: true })
}

export function fetchBooking(id: number): Promise<BookingInfo> {
  return request<BookingInfo>(`/bookings/${id}`, { auth: true })
}

export function payBooking(id: number, channel: string): Promise<PayInfo> {
  return request<PayInfo>(`/bookings/${id}/pay`, {
    method: 'POST',
    body: { channel },
    auth: true,
    idempotencyKey: uuid(),
  })
}

export function confirmMockPay(payNo: string): Promise<void> {
  return request<void>(`/payments/mock/${payNo}/confirm`, { method: 'POST', body: {}, auth: true })
}

export function cancelBooking(id: number): Promise<void> {
  return request<void>(`/bookings/${id}/cancel`, {
    method: 'POST',
    body: {},
    auth: true,
    idempotencyKey: uuid(),
  })
}

export interface RescheduleInfo {
  bookingId: number
  diffCents: number
  payNo: string | null
  newItems: ItemInfo[]
}

export interface CheckinTokenInfo {
  token: string
  startAt: string
  endAt: string
}

export function rescheduleBooking(id: number, slotIds: number[]): Promise<RescheduleInfo> {
  return request<RescheduleInfo>(`/bookings/${id}/reschedule`, {
    method: 'POST',
    body: { slotIds },
    auth: true,
    idempotencyKey: uuid(),
  })
}

export function fetchCheckinToken(id: number): Promise<CheckinTokenInfo> {
  return request<CheckinTokenInfo>(`/bookings/${id}/checkin-token`, { auth: true })
}
