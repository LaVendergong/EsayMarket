import { ref } from 'vue'
import type { LoginVO } from '@/types'

const TOKEN_KEY = 'mall_portal_token'
const USER_KEY = 'mall_portal_user'

/**
 * 登录态说明（安全加固后）：
 *
 * - 真正的凭证在 **HttpOnly Cookie** 里（服务端下发，JS 读不到，XSS 偷不走）
 * - localStorage **只存展示用的用户信息**（昵称/角色），**不存 token**
 * - `token` 这个 ref 仅用于「兼容」：灰度期或自动化测试可能直接塞了一个 token 进 localStorage，
 *   此时前端仍会以 Bearer 头发出去。正常登录流程不会再往里写任何东西。
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
  // 不保存 token：凭证由服务端写进 HttpOnly Cookie
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

/** 页面跳转/按钮显隐用的登录判断（凭证在 Cookie 里，这里只能看本地用户信息） */
function isLoggedIn(): boolean {
  return user.value !== null
}

export const auth = { token, user, set, clear, isLoggedIn }
