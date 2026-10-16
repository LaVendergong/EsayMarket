<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import AppErrorBoundary from '@/components/AppErrorBoundary.vue'
</script>

<template>
  <!-- el-config-provider 下发中文语言包。以前是 app.use(ElementPlus, { locale: zhCn }) 一次性给的，
       改成按需注册后必须这样显式提供，否则分页/表格等内置文案会退回英文 -->
  <el-config-provider :locale="zhCn">
    <!-- 包住整棵页面树：任一页面渲染抛异常时显示兜底 UI，而不是白屏 -->
    <AppErrorBoundary>
      <router-view />
    </AppErrorBoundary>
  </el-config-provider>
</template>
