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
  build: {
    // 同 admin-web：第三方库与业务代码分开，发版后用户只重新下载业务代码那个文件。
    // ⚠️ 同样必须用函数式（对象式会让整包入口失去 tree-shaking，见 admin-web/vite.config.ts 的注释）
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('vant')) return 'vendor-vant'
          if (id.includes('@vue/') || id.includes('/vue/') || id.includes('vue-router')) return 'vendor-vue'
          return 'vendor'
        }
      }
    }
  },
  server: {
    // 5173 给了管理后台，这里用 5174 避开；8080 被使用者的 IDEA 进程占着，别碰
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://localhost:8090',
        changeOrigin: true,
        // 同 admin-web：去掉代理带过来的 Origin，让 dev 与生产（同源）行为一致，
        // 也避免换个 dev 端口就被后端 CORS 白名单挡成 403。
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin')
          })
        }
      },
      // 商品演示图由后端当静态资源提供（backend/mall-core/.../static/img）
      '/img': {
        target: 'http://localhost:8090',
        changeOrigin: true
      }
    }
  }
})
