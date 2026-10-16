<script setup lang="ts">
import { onErrorCaptured, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { reportError } from '@/utils/errorHandler'

/**
 * 错误边界：子组件在渲染/生命周期里抛异常时，用一块可读的兜底 UI 顶替它，
 * 而不是让用户对着白屏。Vue 3 没有内置边界组件，靠 onErrorCaptured 实现。
 *
 * 用法：把需要保护的子树塞进默认插槽（见 App.vue 包住 router-view）。
 */
const route = useRoute()
const failed = ref<Error | null>(null)

onErrorCaptured((err) => {
  const e = err instanceof Error ? err : new Error(String(err))
  failed.value = e
  reportError(e, 'errorBoundary')
  // 返回 false 表示「已经处理」，不再向上冒泡（Vue 于是也不会再调 app.config.errorHandler，
  // 所以上面这句 reportError 是这条错误唯一的一次上报，不会重复）
  return false
})

// 切路由就复位：否则出错后再点导航，router-view 还在 v-else 分支里，永远进不去新页面
watch(
  () => route.fullPath,
  () => {
    failed.value = null
  }
)

// 注意：模板里不能直接写 location.reload()——vue-tsc 会把它当组件实例上的属性而报 TS2339
function reload(): void {
  location.reload()
}
</script>

<template>
  <div v-if="failed" class="boundary">
    <el-result icon="error" title="页面出错了" :sub-title="failed.message">
      <template #extra>
        <el-button type="primary" @click="reload">刷新页面</el-button>
      </template>
    </el-result>
  </div>
  <slot v-else />
</template>

<style scoped>
.boundary {
  padding: 48px 16px;
}
</style>
