<script setup lang="ts">
import { onMounted, ref } from 'vue'
import QRCode from 'qrcode'
import { fetchCheckinToken } from '@/api/bookings'

const props = defineProps<{ bookingId: number }>()
const emit = defineEmits<{ close: [] }>()

const qrData = ref('')
const startAt = ref('')
const endAt = ref('')
const error = ref('')

onMounted(async () => {
  try {
    const info = await fetchCheckinToken(props.bookingId)
    startAt.value = info.startAt
    endAt.value = info.endAt
    qrData.value = await QRCode.toDataURL(info.token, { width: 220, margin: 1 })
  } catch (e) {
    error.value = e instanceof Error && 'code' in e ? (e as { message: string }).message : '获取核销码失败'
  }
})
</script>

<template>
  <div class="veil" @click="emit('close')"></div>
  <div class="sheet qr-sheet">
    <div class="sheet-head">
      <b>核销码</b>
      <button class="sheet-close" @click="emit('close')">✕</button>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
    <template v-if="qrData">
      <div class="qr-wrap">
        <img :src="qrData" alt="核销码" />
      </div>
      <div class="qr-window">{{ startAt.slice(11, 16) }} – {{ endAt.slice(11, 16) }}</div>
    </template>
  </div>
</template>
