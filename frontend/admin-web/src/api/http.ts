import axios from 'axios'
import { ElMessage } from 'element-plus'
import { auth } from '@/stores/auth'

/**
 * 统一出口：
 * - 成功：直接把 R.data 交给调用方（业务码 200）
 * - 业务失败：HTTP 200 但 code != 200，弹错误并 reject
 * - 未认证：真正的 HTTP 401（body 为空），清 token 回登录页
 */
export const http = axios.create({ baseURL: '/api/v1', timeout: 15000, withCredentials: true })

/** 读非 HttpOnly 的 CSRF Cookie（服务端下发，前端放进请求头做双提交校验） */
function readCsrf(): string {
  const hit = document.cookie.split('; ').find((c) => c.startsWith('mall_csrf='))
  return hit ? decodeURIComponent(hit.slice('mall_csrf='.length)) : ''
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
        ElMessage.error(body.message || '请求失败')
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      return body.data
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message
    if (status === 401) {
      auth.clear()
      ElMessage.error('登录已失效，请重新登录')
      if (!window.location.hash.startsWith('#/login')) {
        window.location.hash = '#/login'
      }
    } else if (status) {
      ElMessage.error(message || `请求失败（HTTP ${status}）`)
    } else {
      ElMessage.error('网络异常：请确认后端 mall-admin(8081) 已启动')
    }
    return Promise.reject(error)
  }
)
