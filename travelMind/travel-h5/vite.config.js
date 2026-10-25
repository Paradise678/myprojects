import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      // 开发环境下把 /api 请求代理到本地后端，生产环境由 Nginx 承担此职责
      '/api': {
        target: 'http://localhost:3200',
        changeOrigin: true,
      },
    },
  },
})
