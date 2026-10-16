<script setup lang="ts">
import AppErrorBoundary from '@/components/AppErrorBoundary.vue'
</script>

<template>
  <!-- 包住整棵页面树：任一页面渲染抛异常时显示兜底 UI，而不是白屏 -->
  <AppErrorBoundary>
    <!-- key 用 fullPath：同一路由只换参数（/product/1 → /product/2、/order/1 → /order/2）时
         Vue Router 会复用组件实例、onMounted 不再执行，页面会停在旧数据上；强制重挂载避免这个坑 -->
    <router-view :key="$route.fullPath" />
  </AppErrorBoundary>
</template>
