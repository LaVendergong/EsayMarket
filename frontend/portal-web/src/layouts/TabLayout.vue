<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { cart } from '@/stores/cart'

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
    <router-view :key="route.fullPath" />
    <van-tabbar :model-value="active" @change="go">
      <van-tabbar-item name="home" icon="wap-home-o">首页</van-tabbar-item>
      <van-tabbar-item name="cart" icon="shopping-cart-o" :badge="badge">购物车</van-tabbar-item>
      <van-tabbar-item name="mine" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>
