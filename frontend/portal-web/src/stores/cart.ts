import { ref } from 'vue'
import { cartApi } from '@/api'
import { auth } from './auth'

/** 底部 tabbar 的角标：购物车里的商品件数 */
const count = ref(0)

/** 用已有的购物车数据算角标，**不发请求**（购物车页本地改动后调它，避免多一次往返） */
function sync(list: { quantity?: number }[]) {
  count.value = list.reduce((n, c) => n + (c.quantity || 0), 0)
}

async function refresh() {
  if (!auth.isLoggedIn()) {
    count.value = 0
    return
  }
  try {
    sync(await cartApi.list())
  } catch {
    count.value = 0
  }
}

function clear() {
  count.value = 0
}

export const cart = { count, refresh, sync, clear }
