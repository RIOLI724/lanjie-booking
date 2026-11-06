import { request } from './http'

export interface VenueInfo {
  name: string
  address: string
  phone: string
  openTime: string
  closeTime: string
}

export interface CourtInfo {
  id: number
  name: string
  status: string
}

export interface SlotInfo {
  id: number
  courtId: number
  startAt: string
  endAt: string
  priceCents: number
  status: string
}

export interface CalendarDay {
  date: string
  openTime: string
  closeTime: string
  availableCount: number
}

export function fetchVenue(): Promise<VenueInfo> {
  return request<VenueInfo>('/venue', { auth: true })
}

export function fetchCourts(): Promise<CourtInfo[]> {
  return request<CourtInfo[]>('/courts', { auth: true })
}

export function fetchSlots(date: string): Promise<SlotInfo[]> {
  return request<SlotInfo[]>(`/slots?date=${date}`, { auth: true })
}

export function fetchCalendar(from: string, to: string): Promise<CalendarDay[]> {
  return request<CalendarDay[]>(`/slots/calendar?from=${from}&to=${to}`, { auth: true })
}
