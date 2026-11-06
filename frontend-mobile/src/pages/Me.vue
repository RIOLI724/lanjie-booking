<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import DockBar from '@/components/DockBar.vue'
import { loadMe, logout, sessionState } from '@/stores/session'
import { fetchMember, type MemberInfo } from '@/api/services'

const router = useRouter()
const member = ref<MemberInfo | null>(null)

onMounted(async () => {
  if (!sessionState.loaded) {
    loadMe().catch(() => {})
  }
  member.value = await fetchMember().catch(() => null)
})

async function onLogout() {
  await logout()
  router.push('/login')
}
</script>

<template>
  <div class="page">
    <h1 class="page-title">我的</h1>

    <div class="card" style="display: flex; align-items: center; gap: 12px">
      <span
        style="
          width: 46px;
          height: 46px;
          border-radius: 50%;
          background: linear-gradient(135deg, var(--deep-2), var(--ink));
          color: var(--lime);
          display: grid;
          place-items: center;
          font-weight: 700;
        "
      >
        {{ sessionState.me?.nickname?.slice(0, 1) ?? '球' }}
      </span>
      <div style="flex: 1">
        <b>{{ sessionState.me?.nickname ?? '…' }}</b>
        <div style="font-size: 12px; color: var(--muted); margin-top: 2px">
          {{ sessionState.me?.phoneMasked ?? '' }}
        </div>
      </div>
      <span
        style="font-size: 10px; color: var(--deep-2); background: var(--paper); border-radius: 999px; padding: 3px 8px"
      >
        顾客
      </span>
    </div>

    <router-link to="/orders" class="card row-card">
      <b>我的订单</b>
      <span class="go">›</span>
    </router-link>

    <router-link to="/wallet" class="card row-card" style="margin-top: 10px">
      <b>我的钱包</b>
      <span class="go">›</span>
    </router-link>

    <div class="card" style="margin-top: 10px">
      <div style="display: flex; justify-content: space-between; align-items: baseline">
        <div>
          <b>{{ member?.levelName ?? '普通' }}</b>
          <div style="font-size: 11px; color: var(--muted); margin-top: 2px">
            累计充值 ¥{{ (member?.cumulativeRechargeCents ?? 0) / 100 }}
          </div>
        </div>
        <div style="text-align: right">
          <b style="color: var(--deep-2)">{{ member?.pointsBalance ?? 0 }}</b>
          <div style="font-size: 11px; color: var(--muted)">积分</div>
        </div>
      </div>
    </div>

    <button class="btn-ghost" style="width: 100%; padding: 11px; margin-top: 14px" @click="onLogout">退出登录</button>

    <DockBar />
  </div>
</template>
