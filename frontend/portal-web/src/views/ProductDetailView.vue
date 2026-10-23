<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showFailToast, showImagePreview, showSuccessToast, showToast } from 'vant'
import { cartApi, favoriteApi, productApi, reviewApi } from '@/api'
import { auth } from '@/stores/auth'
import { cart } from '@/stores/cart'
import type { ProductDetailVO, ReviewVO, SkuVO } from '@/types'
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
/** 当前选中规格是否已售罄：售罄时按钮直接置灰，而不是点了才报错（主流电商都这么做） */
const soldOut = computed(() => !selected.value || (selected.value.availableStock ?? 0) <= 0)

function pickSku(sku: SkuVO) {
  if (sku.availableStock <= 0) {
    showToast('该规格无货')
    return
  }
  selected.value = sku
  quantity.value = 1
}

/** 评价：先展示 5 条，点「查看更多」继续加载 */
const reviews = ref<ReviewVO[]>([])
const reviewTotal = ref(0)
const reviewPage = ref(0)
const reviewLoading = ref(false)

/** 收藏状态：未登录不查（避免 401 报错），点了再去登录页 */
const favorited = ref(false)

async function toggleFavorite() {
  if (!product.value) return
  if (!auth.isLoggedIn()) {
    router.push('/login')
    return
  }
  try {
    favorited.value = await favoriteApi.toggle(product.value.id)
    showToast(favorited.value ? '已收藏' : '已取消收藏')
  } catch {
    // 拦截器已提示
  }
}

function previewImages(images: string[], start: number) {
  showImagePreview({ images, startPosition: start })
}

async function loadReviews(reset = false) {
  if (!product.value || reviewLoading.value) return
  if (!reset && reviews.value.length >= reviewTotal.value) return
  reviewLoading.value = true
  try {
    const next = reset ? 1 : reviewPage.value + 1
    const p = await reviewApi.byProduct(product.value.id, next, 5)
    reviews.value = reset ? p.list : [...reviews.value, ...p.list]
    reviewTotal.value = p.total
    reviewPage.value = next
  } catch {
    // 拦截器已提示
  } finally {
    reviewLoading.value = false
  }
}

async function load() {
  const raw = route.params.id
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  loading.value = true
  try {
    const d = await productApi.detail(id)
    product.value = d
    selected.value = d.skus.find((s) => s.availableStock > 0) ?? d.skus[0] ?? null
    reviews.value = []
    reviewTotal.value = 0
    reviewPage.value = 0
    await loadReviews(true)
    // 收藏状态是次要信息：查失败不影响页面
    favorited.value = auth.isLoggedIn() ? await favoriteApi.check(d.id).catch(() => false) : false
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
 * 立即购买：跳到确认订单页并带上「买哪个 SKU、几件」。
 *
 * 走的是 /portal/order/direct 直连下单，**不读也不写购物车**，
 * 所以不会像老实现那样把用户购物车里原有的勾选改掉。
 */
function buyNow() {
  if (!selected.value) {
    showToast('请选择规格')
    return
  }
  if (selected.value.availableStock <= 0) {
    showFailToast('该规格无货')
    return
  }
  router.push({
    path: '/checkout',
    query: {
      productId: String(product.value?.id ?? ''),
      skuId: String(selected.value.id),
      quantity: String(quantity.value)
    }
  })
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
        <!-- 运费规则由后端拼好：常见诉求是「下单前就知道要不要运费」，别等结算页才告诉用户 -->
        <div v-if="product.freightText" class="freight">物流：{{ product.freightText }}</div>
        <!-- 虚拟商品没有物流：必须在下单前说清楚"付款后怎么拿到东西" -->
        <div v-if="product.productType === 2" class="freight">
          虚拟商品（{{ product.deliveryTypeText }}）：支付成功后自动发放，可在「我的卡券」查看
        </div>
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

      <van-cell-group inset class="block">
        <van-cell title="商品评价" :value="reviewTotal ? `${reviewTotal} 条` : '暂无评价'" />
        <div v-if="reviews.length" class="reviews">
          <div v-for="r in reviews" :key="r.id" class="review">
            <div class="review-head">
              <span class="review-user">{{ r.userLabel }}</span>
              <van-rate :model-value="r.rating" readonly :size="12" color="#ee0a24" void-color="#e5e5e5" />
            </div>
            <div v-if="r.content" class="review-content">{{ r.content }}</div>
            <div v-if="r.images?.length" class="review-images">
              <van-image
                v-for="(img, i) in r.images"
                :key="i"
                :src="img"
                width="72"
                height="72"
                fit="cover"
                radius="4"
                @click="previewImages(r.images, i)"
              />
            </div>
            <div class="review-meta">
              {{ r.spec || '默认' }} · {{ (r.createTime || '').slice(0, 10) }}
              <van-tag v-if="r.refunded === 1" type="default" plain>该订单已退款</van-tag>
            </div>
          </div>
          <div v-if="reviews.length < reviewTotal" class="review-more">
            <van-button size="small" plain block :loading="reviewLoading" @click="loadReviews()">
              查看更多评价
            </van-button>
          </div>
        </div>
        <div v-else class="muted review-empty">还没有人评价</div>
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
      <van-action-bar-icon
        :icon="favorited ? 'star' : 'star-o'"
        :color="favorited ? '#ee0a24' : undefined"
        :text="favorited ? '已收藏' : '收藏'"
        @click="toggleFavorite"
      />
      <van-action-bar-button
        type="warning"
        :text="soldOut ? '已售罄' : '加入购物车'"
        :disabled="soldOut"
        :loading="submitting"
        @click="addToCart"
      />
      <van-action-bar-button
        type="danger"
        :text="soldOut ? '已售罄' : '立即购买'"
        :disabled="soldOut"
        :loading="submitting"
        @click="buyNow"
      />
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

.freight {
  margin-top: 6px;
  font-size: 12px;
  color: #ee0a24;
}

.reviews {
  padding: 0 16px 12px;
}

.review {
  padding: 10px 0;
  border-bottom: 1px solid #f5f6f7;
}

.review:last-of-type {
  border-bottom: none;
}

.review-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.review-user {
  font-size: 13px;
  color: #646566;
}

.review-content {
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}

.review-images {
  display: flex;
  gap: 6px;
  margin-top: 8px;
}

.review-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #c8c9cc;
}

.review-more {
  padding-top: 10px;
}

.review-empty {
  padding: 16px;
  text-align: center;
  font-size: 13px;
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
