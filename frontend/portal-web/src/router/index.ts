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
        // 挂在 Tabbar 布局的 children 下（不是顶层）：错误发生在「内容区」，
        // 正好验证布局那层错误边界能保住底部 Tabbar
        path: '__error-demo',
        name: 'errorDemo',
        component: () => import('@/views/ErrorDemoView.vue'),
        meta: { title: '错误兜底演示' }
      }
    ]
  : []

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' }
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('@/views/RegisterView.vue'),
      meta: { public: true, title: '注册' }
    },
    {
      path: '/',
      component: () => import('@/layouts/TabLayout.vue'),
      redirect: '/home',
      children: [
        {
          path: 'home',
          name: 'home',
          component: () => import('@/views/HomeView.vue'),
          meta: { title: 'Mall 商城' }
        },
        {
          path: 'cart',
          name: 'cart',
          component: () => import('@/views/CartView.vue'),
          meta: { title: '购物车', auth: true }
        },
        {
          path: 'mine',
          name: 'mine',
          component: () => import('@/views/MineView.vue'),
          meta: { title: '我的' }
        },
        ...devOnlyRoutes
      ]
    },
    {
      path: '/product/:id',
      name: 'product',
      component: () => import('@/views/ProductDetailView.vue'),
      meta: { title: '商品详情' }
    },
    {
      path: '/checkout',
      name: 'checkout',
      component: () => import('@/views/CheckoutView.vue'),
      meta: { title: '确认订单', auth: true }
    },
    {
      path: '/orders',
      name: 'orders',
      component: () => import('@/views/OrderListView.vue'),
      meta: { title: '我的订单', auth: true }
    },
    {
      path: '/order/:id',
      name: 'orderDetail',
      component: () => import('@/views/OrderDetailView.vue'),
      meta: { title: '订单详情', auth: true }
    },
    {
      path: '/pay/:orderId',
      name: 'cashier',
      component: () => import('@/views/CashierView.vue'),
      meta: { title: '收银台', auth: true }
    },
    {
      path: '/after-sale',
      name: 'afterSaleList',
      component: () => import('@/views/AfterSaleListView.vue'),
      meta: { title: '我的售后', auth: true }
    },
    {
      path: '/after-sale/new',
      name: 'afterSaleApply',
      component: () => import('@/views/AfterSaleApplyView.vue'),
      meta: { title: '申请售后', auth: true }
    },
    {
      path: '/after-sale/:id',
      name: 'afterSaleDetail',
      component: () => import('@/views/AfterSaleDetailView.vue'),
      meta: { title: '售后详情', auth: true }
    },
    {
      path: '/review/:orderItemId',
      name: 'review-edit',
      component: () => import('@/views/ReviewEditView.vue'),
      meta: { title: '评价商品', auth: true }
    },
    {
      path: '/favorites',
      name: 'favorites',
      component: () => import('@/views/FavoritesView.vue'),
      meta: { title: '我的收藏', auth: true }
    },
    {
      path: '/digital',
      name: 'digital',
      component: () => import('@/views/DigitalView.vue'),
      meta: { title: '我的卡券', auth: true }
    },
    {
      path: '/coupon',
      name: 'coupon',
      component: () => import('@/views/CouponView.vue'),
      meta: { title: '优惠券', auth: true }
    },
    {
      path: '/address',
      name: 'address',
      component: () => import('@/views/AddressListView.vue'),
      meta: { title: '收货地址', auth: true }
    },
    {
      path: '/address/new',
      name: 'addressNew',
      component: () => import('@/views/AddressEditView.vue'),
      meta: { title: '新增地址', auth: true }
    },
    {
      path: '/address/:id/edit',
      name: 'addressEdit',
      component: () => import('@/views/AddressEditView.vue'),
      meta: { title: '编辑地址', auth: true }
    },
    { path: '/:pathMatch(.*)*', redirect: '/home' }
  ],
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (to.meta.auth && !auth.isLoggedIn()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  document.title = (to.meta.title as string) || 'Mall 商城'
})

export default router
