<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import DockBar from '@/components/DockBar.vue'
import { loadMe, logout, sessionState } from '@/stores/session'

const router = useRouter()

onMounted(() => {
  if (!sessionState.loaded) {
    loadMe().catch(() => {})
  }
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

    <p class="section-label">以下入口随排期逐周开通</p>
    <div class="card disabled">
      <div>我的订单 · W3</div>
      <div style="border-top: 1px solid var(--line-2); margin: 8px 0"></div>
      <div>我的钱包 · W4</div>
      <div style="border-top: 1px solid var(--line-2); margin: 8px 0"></div>
      <div>会员与积分 · W8</div>
    </div>

    <button class="btn-ghost" style="width: 100%; padding: 11px; margin-top: 14px" @click="onLogout">退出登录</button>

    <DockBar />
  </div>
</template>
