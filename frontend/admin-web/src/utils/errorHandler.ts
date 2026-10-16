import type { App } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * 全局错误兜底。
 *
 * **为什么必须有**：组件在渲染 / 生命周期 / 事件处理里抛出的未捕获异常，如果不注册 errorHandler，
 * 用户看到的是**整页白屏**（控制台里只有一条 stack），而 `pnpm build` 与 `vue-tsc` 完全发现不了。
 *
 * 这里做两件事：
 *   1. 一律 `console.error` 出去（既便于排查，也是 `scripts\web-check.mjs` 的失败信号）
 *   2. 给用户一条可读提示；同一个错误短时间内只提示一次，避免渲染循环里刷屏
 */
const recent = new Map<string, number>()
const DEDUPE_MS = 5000

function shouldReport(message: string): boolean {
  const now = Date.now()
  if (now - (recent.get(message) ?? 0) < DEDUPE_MS) {
    return false
  }
  recent.set(message, now)
  return true
}

/** 统一上报入口：既记控制台，也给用户提示 */
export function reportError(err: unknown, context: string): void {
  console.error(`[${context}]`, err)
  const message = err instanceof Error ? err.message : String(err)
  if (shouldReport(message)) {
    ElMessage.error(`页面出错了：${message}`)
  }
}

export function installGlobalErrorHandler(app: App): void {
  // 组件树里没有被任何错误边界 onErrorCaptured 拦下的异常
  app.config.errorHandler = (err, _instance, info) => {
    reportError(err, `vue:${info}`)
  }

  // Promise 忘记 catch（例如 store 里的异步动作）
  window.addEventListener('unhandledrejection', (e) => {
    reportError(e.reason, 'unhandledrejection')
  })

  // 普通脚本错误。只报 e.error 非空的，避免把「资源加载失败」
  // 和浏览器自身的 ResizeObserver loop 噪声也算成应用异常
  window.addEventListener('error', (e) => {
    if (e.error) {
      reportError(e.error, 'window.error')
    }
  })
}
