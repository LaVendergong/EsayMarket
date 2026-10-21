import { ref } from 'vue'
import type { LoginVO } from '@/types'

const TOKEN_KEY = 'mall_admin_token'
const USER_KEY = 'mall_admin_user'

/**
 * 登录态说明（安全加固后）：
 *
 * - 真正的凭证在 **HttpOnly Cookie** 里（服务端下发，JS 读不到，XSS 偷不走）
 * - localStorage **只存展示用的用户信息**，**不存 token**
 * - `token` ref 仅用于兼容（自动化测试可能塞了 token 进 localStorage 走 Bearer）
 */
function readUser(): LoginVO | null {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

const token = ref(localStorage.getItem(TOKEN_KEY) || '')
const user = ref<LoginVO | null>(readUser())

function set(vo: LoginVO) {
  token.value = ''
  localStorage.removeItem(TOKEN_KEY)
  const safe = { ...vo, token: '' }
  user.value = safe
  localStorage.setItem(USER_KEY, JSON.stringify(safe))
}

function clear() {
  token.value = ''
  user.value = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/** 页面跳转/菜单显隐用的登录判断 */
function isLoggedIn(): boolean {
  return user.value !== null
}

export const auth = { token, user, set, clear, isLoggedIn }
