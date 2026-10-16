import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    // 5173 给了管理后台，这里用 5174 避开；8080 被使用者的 IDEA 进程占着，别碰
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true
      },
      // 商品演示图由后端当静态资源提供（backend/mall-core/.../static/img）
      '/img': {
        target: 'http://localhost:8090',
        changeOrigin: true
      }
    }
  }
})
