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
    // 把「很少变动的第三方库」与「天天改的业务代码」拆成不同文件：
    // 发版后用户只需重新下载业务代码那个 chunk，第三方库命中浏览器缓存。
    //
    // ⚠️ 必须用**函数式**，不能用 `{ 'vendor-element-plus': ['element-plus'] }` 这种对象式：
    // 对象式会把整包的入口模块当成 chunk 入口，入口的导出不会被 tree-shake，
    // 实测 element-plus 从 ~380 kB 反弹到 921 kB（总体积反而变大）。
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          // element-plus 刻意**不拆**：一旦把它的模块划进独立 chunk，
          // 实测体积从 ~396 kB 反弹到 799 kB（chunk 边界会阻止一部分 tree-shaking）。
          // 让它和其它依赖一起由 Rollup 自己决定落在哪个 chunk。
          if (id.includes('element-plus')) return undefined
          if (id.includes('@vue/') || id.includes('/vue/') || id.includes('vue-router')) return 'vendor-vue'
          return 'vendor'
        }
      }
    }
  },
  server: {
    port: 5173,
    // 后端 mall-admin 跑在 8081；走代理避免跨域，也免去在代码里写死端口
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        // 走代理时浏览器会把 Origin 带成 dev server 的地址（换端口就变，例如 5175），
        // 后端 CORS 白名单里没有它 -> 直接 403。生产是同源（nginx 反代 /api），
        // 浏览器压根不发 Origin、不走 CORS，所以这里把头去掉，让 dev 行为和生产一致。
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin')
          })
        }
      },
      // 商品演示图由后端当静态资源提供（backend/mall-core/.../static/img）
      '/img': {
        target: 'http://localhost:8081',
        changeOrigin: true
      },
      // 评价晒图：两个应用指向同一个 uploads 目录，所以 8081 也能读到 portal 上传的文件
      '/uploads': {
        target: 'http://localhost:8081',
        changeOrigin: true
      }
    }
  }
})
