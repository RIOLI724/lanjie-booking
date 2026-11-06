<script setup lang="ts">
import { onMounted, ref } from 'vue'
import DockBar from '@/components/DockBar.vue'
import { fetchPlans, fetchTxns, fetchWallet, recharge, type RechargePlanInfo, type WalletInfo, type WalletTxnInfo } from '@/api/wallet'
import { ApiError } from '@/api/http'

const wallet = ref<WalletInfo | null>(null)
const plans = ref<RechargePlanInfo[]>([])
const txns = ref<WalletTxnInfo[]>([])
const busy = ref(false)
const error = ref('')

const TXN_LABEL: Record<string, string> = {
  RECHARGE: '充值',
  RECHARGE_BONUS: '赠送',
  PAY: '消费',
  REFUND: '退款',
  ADJUST: '调整',
}

async function load() {
  wallet.value = await fetchWallet()
  txns.value = await fetchTxns()
}

async function onRecharge(plan: RechargePlanInfo) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await recharge(plan.id)
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '网络异常，请稍后再试'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  plans.value = await fetchPlans()
  await load()
})
</script>

<template>
  <div class="page">
    <h1 class="page-title">我的钱包</h1>

    <div class="card balance-card">
      <div class="balance-num">¥{{ (wallet?.balanceCents ?? 0) / 100 }}</div>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <div class="grid">
      <button
        v-for="plan in plans"
        :key="plan.id"
        class="card plan-card"
        :disabled="busy"
        @click="onRecharge(plan)"
      >
        <b>¥{{ plan.amountCents / 100 }}</b>
        <small>送 ¥{{ plan.bonusCents / 100 }}</small>
      </button>
    </div>

    <div class="card" style="margin-top: 12px">
      <div v-for="(t, i) in txns" :key="i" class="txn-row">
        <div>
          <div>{{ TXN_LABEL[t.type] ?? t.type }}</div>
          <div class="txn-time">{{ t.createdAt.slice(5, 16).replace('T', ' ') }}</div>
        </div>
        <div class="txn-amt">
          <b :class="t.amountCents >= 0 ? 'in' : 'out'">
            {{ t.amountCents >= 0 ? '+' : '' }}{{ t.amountCents / 100 }}
          </b>
          <div class="txn-time">余 {{ t.balanceAfterCents / 100 }}</div>
        </div>
      </div>
    </div>

    <DockBar />
  </div>
</template>
