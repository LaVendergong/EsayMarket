import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { auth } from '@/stores/auth'

/**
 * 只在 dev 构建里注册的「故意抛错」页面，用来让错误边界可被自动化验证
 * （`scripts\web-check.mjs` 会访问它并断言出现兜底 UI 而不是白屏）。
 * `import.meta.env.DEV` 在生产构建里是常量 false，这一整个分支会被当死代码删掉。
 */
const devOnlyRoutes: RouteRecordRaw[] = import.meta.env.DEV
  ? [
      {
        // 挂在布局的 children 下（不是顶层）：这样错误发生在「内容区」，
        // 正好验证布局那层错误边界能保住侧边栏
        path: '__error-demo',
        name: 'errorDemo',
        component: () => import('@/views/ErrorDemoView.vue'),
        meta: { public: true, title: '错误兜底演示' }
      }
    ]
  : []

const router = createRouter({
  // 用 hash 路由：静态部署不用配 rewrite，401 回登录页也不必依赖 router 实例
  history: createWebHashHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' }
    },
    {
      path: '/',
      component: () => import('@/layouts/AdminLayout.vue'),
      redirect: '/product',
      children: [
        {
          path: 'category',
          name: 'category',
          component: () => import('@/views/CategoryView.vue'),
          meta: { title: '分类管理' }
        },
        {
          path: 'product',
          name: 'product',
          component: () => import('@/views/ProductListView.vue'),
          meta: { title: '商品管理' }
        },
        {
          path: 'product/new',
          name: 'productNew',
          component: () => import('@/views/ProductEditView.vue'),
          meta: { title: '新增商品' }
        },
        {
          path: 'product/:id/edit',
          name: 'productEdit',
          component: () => import('@/views/ProductEditView.vue'),
          meta: { title: '编辑商品' }
        },
        {
          // 放在 /product/new 与 /product/:id/edit 之后：前者是字面量优先，后者段数不同，都不会被这里吃掉
          path: 'product/:id',
          name: 'productDetail',
          component: () => import('@/views/ProductDetailView.vue'),
          meta: { title: '商品详情' }
        },
        {
          path: 'order',
          name: 'order',
          component: () => import('@/views/OrderListView.vue'),
          meta: { title: '订单管理' }
        },
        {
          path: 'order/:id',
          name: 'orderDetail',
          component: () => import('@/views/OrderDetailView.vue'),
          meta: { title: '订单详情' }
        },
        ...devOnlyRoutes
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!auth.token.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  document.title = `${(to.meta.title as string) || ''} · Mall 管理后台`
})

export default router
