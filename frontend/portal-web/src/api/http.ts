import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { showFailToast } from 'vant'
import { auth } from '@/stores/auth'

/**
 * 统一出口，规则同后端 §6.1：
 * - 成功：直接返回 R.data
 * - 业务失败：HTTP 200 但 code != 200，弹提示并 reject
 * - 未认证：真正的 HTTP 401，先用 refresh 换新 access 再重试一次；换不到才清登录态回登录页
 *
 * 登录态都在 **HttpOnly Cookie** 里（access 15 分钟 / refresh 7 天），前端拿不到也存不下 token。
 */
export const http = axios.create({ baseURL: '/api/v1', timeout: 15000, withCredentials: true })

/** 重试标记挂在 config 上（axios 不反对额外字段） */
interface RetriableConfig extends AxiosRequestConfig {
  __retried?: boolean
}

/** 只给认证接口用的裸实例：**不挂拦截器**，避免刷新失败时又弹一次 toast、又触发一次刷新 */
const raw = axios.create({ baseURL: '/api/v1', timeout: 15000, withCredentials: true })

/** 读非 HttpOnly 的 CSRF Cookie（服务端下发，前端放进请求头做双提交校验） */
function readCsrf(): string {
  const hit = document.cookie.split('; ').find((c) => c.startsWith('mall_csrf='))
  return hit ? decodeURIComponent(hit.slice('mall_csrf='.length)) : ''
}

/**
 * 单飞（single-flight）：多个并发请求同时 401 时只发**一次**刷新请求。
 * 不做的话，refresh 是「用一次即作废」的，第二个并发刷新必然失败并把用户踢下线。
 */
let refreshing: Promise<boolean> | null = null
function refreshOnce(): Promise<boolean> {
  if (refreshing) return refreshing
  const p = raw
    .post('/portal/auth/refresh')
    .then((resp) => resp.data?.code === 200)
    .catch(() => false)
    .then((ok) => {
      refreshing = null
      return ok
    })
  refreshing = p
  return p
}

http.interceptors.request.use((config) => {
  // 登录态默认走 HttpOnly Cookie（浏览器自动携带）。只有灰度期/自动化测试塞进来的旧 token 才走 Bearer。
  if (auth.token.value) {
    config.headers.Authorization = `Bearer ${auth.token.value}`
    return config
  }
  const method = (config.method || 'get').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    const csrf = readCsrf()
    if (csrf) config.headers['X-CSRF-Token'] = csrf
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code === 'number') {
      if (body.code !== 200) {
        showFailToast(body.message || '请求失败')
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      return body.data
    }
    return body
  },
  async (error) => {
    const status = error.response?.status
    const cfg = error.config as RetriableConfig | undefined
    const url = String(cfg?.url || '')

    // access 过期（15 分钟）是日常事件，不该让用户重新登录：先换一次再重试原请求
    if (status === 401 && cfg && !cfg.__retried && !url.includes('/auth/')) {
      const ok = await refreshOnce()
      if (ok) {
        cfg.__retried = true
        return http.request(cfg)
      }
      auth.clear()
      showFailToast('登录已失效')
      if (!window.location.hash.startsWith('#/login')) {
        window.location.hash = '#/login'
      }
      return Promise.reject(error)
    }

    const message = error.response?.data?.message
    if (status === 401) {
      auth.clear()
      showFailToast('登录已失效')
      if (!window.location.hash.startsWith('#/login')) {
        window.location.hash = '#/login'
      }
    } else if (status) {
      showFailToast(message || `请求失败（HTTP ${status}）`)
    } else {
      showFailToast('网络异常：请确认后端 mall-portal(8090) 已启动')
    }
    return Promise.reject(error)
  }
)
