import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5180,
    host: true,
    proxy: {
      '/api': { target: 'http://localhost:8082', changeOrigin: true },
    },
  },
})
