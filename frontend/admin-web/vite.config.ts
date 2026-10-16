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
    port: 5173,
    // 后端 mall-admin 跑在 8081；走代理避免跨域，也免去在代码里写死端口
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true
      },
      // 商品演示图由后端当静态资源提供（backend/mall-core/.../static/img）
      '/img': {
        target: 'http://localhost:8081',
        changeOrigin: true
      }
    }
  }
})
