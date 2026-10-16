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
const checkedList = computed(() => list.value.filter((c) => c.valid && c.checked === 1))
const allChecked = computed(() => validList.value.length > 0 && checkedList.value.length === validList.value.length)
const total = computed(() => checkedList.value.reduce((s, c) => s + Number(c.totalPrice || 0), 0))

async function load() {
  loading.value = true
  try {
    list.value = await cartApi.list()
    await cart.refresh()
  } finally {
    loading.value = false
  }
}

async function toggleCheck(c: CartVO, value: boolean) {
  await cartApi.updateChecked(c.id, value ? 1 : 0)
  await load()
}

async function toggleAll(value: boolean) {
  for (const c of validList.value) {
    const want = value ? 1 : 0
    if (c.checked !== want) await cartApi.updateChecked(c.id, want)
  }
  await load()
}

async function changeQuantity(c: CartVO, value: number | string) {
  await cartApi.updateQuantity(c.id, Number(value))
  await load()
}

async function removeItem(c: CartVO) {
  try {
    await showConfirmDialog({ title: '提示', message: '确定从购物车移除该商品？' })
  } catch {
    return
  }
  await cartApi.remove(c.id)
  showSuccessToast('已移除')
  await load()
}

async function clearChecked() {
  try {
    await showConfirmDialog({ title: '提示', message: '确定清空所有已选中的商品？' })
  } catch {
    return
  }
  await cartApi.clearChecked()
  await load()
}

onMounted(load)
</script>

<template>
  <div class="pb-cart">
    <van-nav-bar title="购物车">
      <template #right>
        <span v-if="checkedList.length" class="clear" @click="clearChecked">清空选中</span>
      </template>
    </van-nav-bar>

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
      @submit="router.push('/checkout')"
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
