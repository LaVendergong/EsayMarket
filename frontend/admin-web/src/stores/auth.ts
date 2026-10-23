import { computed, ref } from 'vue'
import { authApi } from '@/api'
import type { LoginVO } from '@/types'

const TOKEN_KEY = 'mall_admin_token'
const USER_KEY = 'mall_admin_user'

/**
 * 登录态说明（安全加固后）：
 *
 * - 真正的凭证在 **HttpOnly Cookie** 里（服务端下发，JS 读不到，XSS 偷不走）
 * - localStorage **只存展示用的用户信息**（含权限码），**不存 token**
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

/** 菜单/路由的权限码集合（超管的权限由后端返回全量） */
const permissions = computed(() => new Set(user.value?.permissions ?? []))

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

function hasPerm(code: string): boolean {
  return permissions.value.has(code)
}

/** 登录后权限变更过（或升版前的旧缓存）时，从服务端补一次 */
async function ensurePermissions(): Promise<void> {
  if (!user.value) return
  if (user.value.permissions) return
  try {
    set(await authApi.me())
  } catch {
    // 401 交给 http 拦截器统一处理，这里不重复弹提示
  }
}

/**
 * 路由/菜单 → 权限码。
 *
 * 集中在一张表里而不是写进每条路由的 meta：改权限只需要动这里，
 * 菜单显隐、路由守卫、权限不足时的兜底跳转用的都是同一份映射。
 */
const ROUTE_PERMISSION: Record<string, string> = {
  '/dashboard': 'dashboard:view',
  '/report': 'report:view',
  '/product': 'product:view',
  '/category': 'category:view',
  '/order': 'order:view',
  '/after-sale': 'after-sale:view',
  '/review': 'review:view',
  '/coupon': 'coupon:view',
  '/freight': 'freight:view',
  '/audit': 'audit:view',
  '/maintenance': 'maintenance:view',
  '/reconcile': 'report:view',
  '/rbac': 'system:rbac'
}

/** 最长前缀匹配：/order/123 归到 /order 的权限 */
function permissionOf(path: string): string | undefined {
  const hit = Object.keys(ROUTE_PERMISSION)
    .filter((k) => path === k || path.startsWith(k + '/'))
    .sort((a, b) => b.length - a.length)[0]
  return hit ? ROUTE_PERMISSION[hit] : undefined
}

/** 权限不足时跳到这里：按菜单顺序找第一个有权限的入口 */
function firstAllowedPath(): string {
  const ordered = ['/dashboard', '/product', '/order', '/after-sale']
  return ordered.find((p) => hasPerm(ROUTE_PERMISSION[p])) ?? ''
}

export const auth = {
  token,
  user,
  permissions,
  set,
  clear,
  isLoggedIn,
  hasPerm,
  ensurePermissions,
  permissionOf,
  firstAllowedPath
}
