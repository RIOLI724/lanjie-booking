import { request } from './http'
import { confirmMockPay } from './bookings'

export interface WalletInfo {
  balanceCents: number
}

export interface WalletTxnInfo {
  type: string
  amountCents: number
  balanceAfterCents: number
  bizType: string
  createdAt: string
}

export interface RechargePlanInfo {
  id: number
  amountCents: number
  bonusCents: number
  title: string
}

export function fetchWallet(): Promise<WalletInfo> {
  return request<WalletInfo>('/wallet', { auth: true })
}

export function fetchTxns(): Promise<WalletTxnInfo[]> {
  return request<WalletTxnInfo[]>('/wallet/transactions', { auth: true })
}

export function fetchPlans(): Promise<RechargePlanInfo[]> {
  return request<RechargePlanInfo[]>('/recharge-plans', { auth: true })
}

/** 选档 → 创建支付单 → 模拟网关确认 → 入账，一气呵成 */
export async function recharge(planId: number): Promise<void> {
  const pay = await request<{ payNo: string }>('/wallet/recharge', {
    method: 'POST',
    body: { planId, channel: 'WECHAT_MOCK' },
    auth: true,
    idempotencyKey: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
  })
  await confirmMockPay(pay.payNo)
}
