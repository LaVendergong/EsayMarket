<template>
  <!-- key 用 fullPath：同一路由只换参数（/product/1 → /product/2、/order/1 → /order/2）时
       Vue Router 会复用组件实例、onMounted 不再执行，页面会停在旧数据上；强制重挂载避免这个坑 -->
  <router-view :key="$route.fullPath" />
</template>
