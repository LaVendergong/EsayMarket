import { ref } from 'vue'
import type { LoginVO } from '@/types'

const TOKEN_KEY = 'mall_admin_token'
const USER_KEY = 'mall_admin_user'

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
  token.value = vo.token
  user.value = vo
  localStorage.setItem(TOKEN_KEY, vo.token)
  localStorage.setItem(USER_KEY, JSON.stringify(vo))
}

function clear() {
  token.value = ''
  user.value = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export const auth = { token, user, set, clear }
