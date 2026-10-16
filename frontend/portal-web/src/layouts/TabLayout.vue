<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { cart } from '@/stores/cart'
import AppErrorBoundary from '@/components/AppErrorBoundary.vue'

const route = useRoute()
const router = useRouter()

const active = computed(() => route.name as string)
const badge = computed(() => (cart.count.value > 0 ? String(cart.count.value) : ''))

// 切到首页/购物车时刷新角标（加购、下单、删除后数量都会变）
watch(
  () => route.fullPath,
  () => {
    if (route.name === 'home' || route.name === 'cart') cart.refresh()
  }
)

onMounted(() => cart.refresh())

function go(name: string) {
  router.push({ name })
}
</script>

<template>
  <div class="app-shell">
    <!-- 内容区单独套一层错误边界：某个页面抛异常时**保住底部 Tabbar**，用户还能切走
         （只靠根 App.vue 那层的话整页会被顶掉，除了刷新无处可去） -->
    <AppErrorBoundary>
      <router-view :key="route.fullPath" />
    </AppErrorBoundary>
    <van-tabbar :model-value="active" @change="go">
      <van-tabbar-item name="home" icon="wap-home-o">首页</van-tabbar-item>
      <van-tabbar-item name="cart" icon="shopping-cart-o" :badge="badge">购物车</van-tabbar-item>
      <van-tabbar-item name="mine" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>
