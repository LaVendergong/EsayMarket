<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast } from 'vant'
import { cartApi } from '@/api'
import { cart } from '@/stores/cart'
import type { CartVO } from '@/types'
import { money } from '@/utils/format'

const router = useRouter()
const list = ref<CartVO[]>([])
const loading = ref(false)

const validList = computed(() => list.value.filter((c) => c.valid))
const invalidList = computed(() => list.value.filter((c) => !c.valid))
const checkedList = computed(() => list.value.filter((c) => c.valid && c.checked === 1))
const allChecked = computed(() => validList.value.length > 0 && checkedList.value.length === validList.value.length)
const total = computed(() => checkedList.value.reduce((s, c) => s + Number(c.totalPrice || 0), 0))

/**
 * 只在「首次进入 / 需要回滚」时整页拉取。
 *
 * 勾选、改数量、删除**都不再重新拉取**：先改本地数据（立即生效），再静默同步服务端。
 * 老实现每次都 `await load()`，点一下 checkbox 整个列表闪一次，是"不丝滑"的主要来源。
 */
async function load(showLoading = true) {
  if (showLoading) loading.value = true
  try {
    list.value = await cartApi.list()
    cart.sync(list.value)
  } finally {
    loading.value = false
  }
}

async function toggleCheck(c: CartVO, value: boolean) {
  const prev = c.checked
  c.checked = value ? 1 : 0
  cart.sync(list.value)
  try {
    await cartApi.updateChecked(c.id, value ? 1 : 0)
  } catch {
    c.checked = prev // 回滚（拦截器已提示）
    cart.sync(list.value)
  }
}

/** 全选：一条请求改完，不再循环发 N 次 */
async function toggleAll(value: boolean) {
  const prev = validList.value.map((c) => c.checked)
  for (const c of validList.value) c.checked = value ? 1 : 0
  cart.sync(list.value)
  try {
    await cartApi.updateAllChecked(value ? 1 : 0)
  } catch {
    validList.value.forEach((c, i) => (c.checked = prev[i]))
    cart.sync(list.value)
  }
}

/**
 * 步进器连点会打出请求风暴：按条目做 350ms 防抖，只把**最后一次**数量发出去。
 * 期间界面已经是新数量（乐观更新），所以用户感觉是"跟着手指走"。
 */
const qtyTimers = new Map<number, number>()

function changeQuantity(c: CartVO, value: number | string) {
  const qty = Math.max(1, Number(value) || 1)
  const prevQty = c.quantity
  const prevTotal = c.totalPrice
  c.quantity = qty
  c.totalPrice = Number((Number(c.price) * qty).toFixed(2))
  cart.sync(list.value)

  const running = qtyTimers.get(c.id)
  if (running) window.clearTimeout(running)
  qtyTimers.set(
    c.id,
    window.setTimeout(async () => {
      qtyTimers.delete(c.id)
      try {
        await cartApi.updateQuantity(c.id, qty)
      } catch {
        c.quantity = prevQty
        c.totalPrice = prevTotal
        cart.sync(list.value)
        await load(false)
      }
    }, 350)
  )
}

async function removeItem(c: CartVO) {
  try {
    await showConfirmDialog({ title: '提示', message: '确定从购物车移除该商品？' })
  } catch {
    return
  }
  const index = list.value.findIndex((x) => x.id === c.id)
  if (index >= 0) list.value.splice(index, 1)
  cart.sync(list.value)
  try {
    await cartApi.remove(c.id)
    showSuccessToast('已移除')
  } catch {
    await load(false)
  }
}

async function clearChecked() {
  try {
    await showConfirmDialog({ title: '提示', message: '确定清空所有已选中的商品？' })
  } catch {
    return
  }
  const removed = new Set(checkedList.value.map((c) => c.id))
  list.value = list.value.filter((c) => !removed.has(c.id))
  cart.sync(list.value)
  try {
    await cartApi.clearChecked()
  } catch {
    await load(false)
  }
}

/** 一键清理失效商品（下架/无货），省得用户一条条划走 */
async function clearInvalid() {
  const invalids = invalidList.value
  if (!invalids.length) return
  const removed = new Set(invalids.map((c) => c.id))
  list.value = list.value.filter((c) => !removed.has(c.id))
  cart.sync(list.value)
  for (const c of invalids) {
    try {
      await cartApi.remove(c.id)
    } catch {
      /* 单条失败不影响其它 */
    }
  }
  showSuccessToast('已清理失效商品')
}

function goCheckout() {
  if (!checkedList.value.length) return
  router.push('/checkout')
}

onMounted(() => load())
</script>

<template>
  <div class="pb-cart">
    <van-nav-bar title="购物车">
      <template #right>
        <span v-if="checkedList.length" class="clear" @click="clearChecked">清空选中</span>
      </template>
    </van-nav-bar>

    <div v-if="invalidList.length" class="invalid-bar">
      <span class="muted">{{ invalidList.length }} 件商品已失效</span>
      <span class="link" @click="clearInvalid">清空失效商品</span>
    </div>

    <van-loading v-if="loading && !list.length" class="loading" />

    <template v-else-if="list.length">
      <van-swipe-cell v-for="c in list" :key="c.id" :disabled="!c.valid">
        <van-card
          :price="money(c.price).replace('¥', '')"
          :title="c.productName || '商品已删除'"
          :desc="c.spec"
          :thumb="c.productImage || ''"
          :class="{ invalid: !c.valid }"
        >
          <template #tags>
            <van-tag v-if="!c.valid" type="default" plain>已失效（下架或无货）</van-tag>
            <van-tag v-else plain type="success">可售 {{ c.availableStock }}</van-tag>
          </template>
          <template #bottom>
            <div class="row">
              <van-checkbox
                :model-value="c.checked === 1"
                :disabled="!c.valid"
                @update:model-value="(v: boolean) => toggleCheck(c, v)"
              />
              <van-stepper
                :model-value="c.quantity"
                :min="1"
                :max="Math.max(c.availableStock || 1, 1)"
                :disabled="!c.valid"
                integer
                @change="(v: number | string) => changeQuantity(c, v)"
              />
            </div>
          </template>
        </van-card>
        <template #right>
          <van-button square type="danger" text="删除" class="del" @click="removeItem(c)" />
        </template>
      </van-swipe-cell>
    </template>

    <van-empty v-else description="购物车还是空的">
      <van-button round type="danger" class="go" @click="router.push('/home')">去逛逛</van-button>
    </van-empty>

    <van-submit-bar
      v-if="list.length"
      :price="Math.round(total * 100)"
      button-text="去结算"
      :disabled="!checkedList.length"
      class="bar"
      @submit="goCheckout"
    >
      <template #tip>
        <div class="tip">
          <van-checkbox :model-value="allChecked" @update:model-value="(v: boolean) => toggleAll(v)">
            全选
          </van-checkbox>
          <span class="muted">已选 {{ checkedList.length }} 件</span>
        </div>
      </template>
    </van-submit-bar>
  </div>
</template>

<style scoped>
/* tabbar 50px + 结算栏 50px，避免两层固定栏互相压住 */
.pb-cart {
  padding-bottom: 108px;
}

.bar {
  bottom: 50px;
}

.loading {
  display: block;
  margin: 40px auto;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0 0;
}

.invalid {
  opacity: 0.6;
}

.del {
  height: 100%;
}

.clear {
  color: #ee0a24;
  font-size: 14px;
}

.invalid-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px;
  background: #fff8e6;
  font-size: 12px;
}

.link {
  color: #ee0a24;
}

.tip {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
}

.go {
  width: 140px;
}
</style>
