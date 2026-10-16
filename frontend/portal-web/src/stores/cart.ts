import { ref } from 'vue'
import { cartApi } from '@/api'
import { auth } from './auth'

/** 底部 tabbar 的角标：购物车里的商品件数 */
const count = ref(0)

async function refresh() {
  if (!auth.token.value) {
    count.value = 0
    return
  }
  try {
    const list = await cartApi.list()
    count.value = list.reduce((n, c) => n + (c.quantity || 0), 0)
  } catch {
    count.value = 0
  }
}

function clear() {
  count.value = 0
}

export const cart = { count, refresh, clear }
