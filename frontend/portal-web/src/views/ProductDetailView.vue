<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showFailToast, showSuccessToast, showToast } from 'vant'
import { cartApi, productApi } from '@/api'
import { cart } from '@/stores/cart'
import type { ProductDetailVO, SkuVO } from '@/types'
import { money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const product = ref<ProductDetailVO | null>(null)
const selected = ref<SkuVO | null>(null)
const quantity = ref(1)
const loading = ref(false)
const submitting = ref(false)

const images = computed(() => {
  const p = product.value
  if (!p) return []
  return [...new Set([p.mainImage, ...p.images].filter(Boolean) as string[])]
})

/** detail 在库里存的是 HTML，这里剥成纯文本显示（不用 v-html，避免 XSS 面） */
const detailText = computed(() => (product.value?.detail || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim())

const price = computed(() => selected.value?.price ?? product.value?.price ?? 0)
const maxQuantity = computed(() => Math.max(selected.value?.availableStock ?? 0, 1))

function pickSku(sku: SkuVO) {
  if (sku.availableStock <= 0) {
    showToast('该规格无货')
    return
  }
  selected.value = sku
  quantity.value = 1
}

async function load() {
  const raw = route.params.id
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  loading.value = true
  try {
    const d = await productApi.detail(id)
    product.value = d
    selected.value = d.skus.find((s) => s.availableStock > 0) ?? d.skus[0] ?? null
  } catch {
    // 拦截器已提示（不存在 -> 2001，已下架 -> 2002）
  } finally {
    loading.value = false
  }
}

async function addToCart() {
  if (!selected.value) {
    showToast('请选择规格')
    return
  }
  submitting.value = true
  try {
    await cartApi.add(selected.value.id, quantity.value)
    await cart.refresh()
    showSuccessToast('已加入购物车')
  } catch {
    // 拦截器已提示（库存不足 -> 2003）
  } finally {
    submitting.value = false
  }
}

/**
 * 立即购买：后端下单只结算「购物车中已选中」的条目，
 * 所以这里先把本 SKU 加购，再把其它条目取消选中（会覆盖用户之前在购物车里的勾选，属于已知取舍）。
 */
async function buyNow() {
  if (!selected.value) {
    showToast('请选择规格')
    return
  }
  submitting.value = true
  try {
    const skuId = selected.value.id
    await cartApi.add(skuId, quantity.value)
    const list = await cartApi.list()
    for (const c of list) {
      const want = c.skuId === skuId && c.valid ? 1 : 0
      if (c.checked !== want) await cartApi.updateChecked(c.id, want)
    }
    await cart.refresh()
    router.push('/checkout')
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="app-shell pb-submit">
    <van-nav-bar title="商品详情" left-arrow @click-left="router.back()" />

    <template v-if="product">
      <van-swipe v-if="images.length" class="gallery" :autoplay="4000" indicator-color="#ee0a24">
        <van-swipe-item v-for="(img, i) in images" :key="i">
          <van-image :src="img" width="100%" height="280" fit="cover" />
        </van-swipe-item>
      </van-swipe>
      <div v-else class="gallery placeholder muted">暂无图片</div>

      <van-cell-group inset class="block">
        <div class="head">
          <div class="price big">{{ money(price) }}</div>
          <div class="muted">销量 {{ product.sales }}</div>
        </div>
        <div class="name">{{ product.name }}</div>
        <div v-if="product.subTitle" class="muted">{{ product.subTitle }}</div>
      </van-cell-group>

      <van-cell-group inset class="block">
        <van-cell title="选择规格" :value="selected ? selected.spec : '无规格'" />
        <div class="skus">
          <van-tag
            v-for="s in product.skus"
            :key="s.id"
            size="large"
            :type="selected?.id === s.id ? 'danger' : 'default'"
            :plain="selected?.id !== s.id"
            :class="['sku', { disabled: s.availableStock <= 0 }]"
            @click="pickSku(s)"
          >
            {{ s.spec || '默认' }}（{{ money(s.price) }}<span v-if="s.availableStock > 0"> / 剩 {{ s.availableStock }}</span><span v-else> / 无货</span>）
          </van-tag>
        </div>
        <van-cell title="购买数量">
          <template #value>
            <van-stepper v-model="quantity" :min="1" :max="maxQuantity" integer />
          </template>
        </van-cell>
      </van-cell-group>

      <van-cell-group v-if="detailText" inset class="block">
        <van-cell title="商品详情" />
        <div class="detail">{{ detailText }}</div>
      </van-cell-group>
    </template>

    <div v-else-if="!loading" class="empty">商品不存在或已下架</div>

    <!-- 注意：Vant 4 没有 goods-action，是 action-bar（GoodsAction 是 Vant 3 的旧组件） -->
    <van-action-bar v-if="product">
      <van-action-bar-icon icon="cart-o" text="购物车" :badge="cart.count.value || ''" @click="router.push('/cart')" />
      <van-action-bar-button type="warning" text="加入购物车" :loading="submitting" @click="addToCart" />
      <van-action-bar-button type="danger" text="立即购买" :loading="submitting" @click="buyNow" />
    </van-action-bar>
  </div>
</template>

<style scoped>
.gallery {
  background: #fff;
}

.placeholder {
  height: 280px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.block {
  margin-top: 12px;
}

.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 12px 16px 4px;
}

.big {
  font-size: 22px;
}

.name {
  padding: 0 16px;
  font-size: 16px;
  font-weight: 600;
}

.skus {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 16px 16px;
}

.sku.disabled {
  opacity: 0.45;
}

.detail {
  padding: 8px 16px 16px;
  white-space: pre-wrap;
  line-height: 1.6;
}
</style>
