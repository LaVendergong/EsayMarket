import { createRouter, createWebHashHistory } from 'vue-router'
import { auth } from '@/stores/auth'

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
        }
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
  if (to.meta.auth && !auth.token.value) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  document.title = (to.meta.title as string) || 'Mall 商城'
})

export default router
