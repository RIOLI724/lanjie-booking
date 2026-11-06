import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // host: true 允许局域网手机真机预览
    port: 5179,
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
})
