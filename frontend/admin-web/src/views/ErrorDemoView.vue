<script setup lang="ts">
/**
 * 故意抛错的演示页，**只用于验证错误边界真的能兜住**（由 `scripts\web-check.mjs` 访问）。
 *
 * 只在 dev 构建里存在：路由注册被 `import.meta.env.DEV` 包着，
 * 生产构建时这个分支是死代码，会被整块删掉（连这个文件的 chunk 都不会产出）。
 *
 * 用 setup 阶段抛错是因为它对应最常见的那类真实事故：模板/computed 里访问了空数据。
 */
throw new Error('__error_demo__')
</script>

<template>
  <div>不该渲染到这里</div>
</template>
