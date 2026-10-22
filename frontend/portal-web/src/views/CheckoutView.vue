<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import { addressApi, cartApi, orderApi, productApi } from '@/api'
import { cart } from '@/stores/cart'
import type { AddressVO, CartVO } from '@/types'
import { money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

/**
 * 下单幂等键：一次结算页会话内固定不变，所以网络抖动重试 / 手快双击都只会落一单。
 * 离开结算页再回来会重新挂载组件（根 router-view 带 :key），自然拿到新的键。
 *
 * 注意 crypto.randomUUID 只在安全上下文（https / localhost）才有——手机用局域网 IP
 * 打开 H5 时它是 undefined，必须有兜底，否则真机上直接白屏报错。
 */
function newRequestId(): string {
  const c = window.crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

const requestId = ref(newRequestId())

/**
 * 「立即购买」入口：商品详情页会带 ?productId=&skuId=&quantity= 跳过来。
 * 有这些参数时走 /portal/order/direct 直连下单，**不读也不写购物车** ——
 * 老实现是「先加购再把其它条目取消勾选」绕出来的，会破坏用户原有的勾选。
 */
const direct = computed(() => {
  const skuId = Number(route.query.skuId)
  if (!route.query.skuId || !Number.isFinite(skuId) || skuId <= 0) return null
  return {
    productId: Number(route.query.productId) || 0,
    skuId,
    quantity: Math.max(Number(route.query.quantity) || 1, 1)
  }
})
const isDirect = computed(() => direct.value !== null)

const addresses = ref<AddressVO[]>([])
const addressId = ref<number | undefined>(undefined)
const items = ref<CartVO[]>([])
const pickerVisible = ref(false)
const submitting = ref(false)
/** 页面数据（地址 + 清单）加载中：这期间不让点提交 */
const loading = ref(true)

const selected = computed(() => addresses.value.find((a) => a.id === addressId.value))
const total = computed(() => items.value.reduce((s, c) => s + Number(c.totalPrice || 0), 0))
const fullAddress = (a: AddressVO) => [a.province, a.city, a.district, a.detail].filter(Boolean).join('')

async function load() {
  loading.value = true
  try {
    await loadData()
  } finally {
    loading.value = false
  }
}

async function loadData() {
  const addrList = await addressApi.list()
  addresses.value = addrList
  addressId.value = (addrList.find((a) => a.isDefault === 1) ?? addrList[0])?.id

  const d0 = direct.value
  if (d0) {
    const detail = await productApi.detail(d0.productId)
    const sku = detail.skus.find((s) => s.id === d0.skuId)
    // 借用 CartVO 的形状复用下面的清单 UI（id 在这里用不到，填 0）
    items.value = sku
      ? [
          {
            id: 0,
            productId: detail.id,
            skuId: sku.id,
            productName: detail.name,
            productImage: sku.image || detail.mainImage,
            spec: sku.spec,
            price: sku.price,
            quantity: d0.quantity,
            checked: 1,
            totalPrice: Number((Number(sku.price) * d0.quantity).toFixed(2))
          }
        ]
      : []
    return
  }

  const cartList = await cartApi.list()
  items.value = cartList.filter((c) => c.valid && c.checked === 1)
}

function choose(id: number) {
  addressId.value = id
  pickerVisible.value = false
}

async function submit() {
  if (!addressId.value) {
    showToast('请先选择收货地址')
    return
  }
  if (!items.value.length) {
    showToast('没有待结算的商品')
    return
  }
  submitting.value = true
  try {
    const d = direct.value
    const vo = d
      ? await orderApi.createDirect(d.skuId, d.quantity, addressId.value, requestId.value)
      : await orderApi.create(addressId.value, requestId.value)
    // 直连下单没动购物车，没必要刷角标
    if (!isDirect.value) await cart.refresh()
    showSuccessToast('下单成功')
    // 下单成功后进收银台选支付方式（不再直接标记已支付）
    router.replace(`/pay/${vo.orderId}`)
  } catch {
    // 拦截器已提示（没有默认地址 -> 2010，购物车为空 -> 2004，库存不足 -> 2003）
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="app-shell pb-submit">
    <van-nav-bar title="确认订单" left-arrow @click-left="router.back()" />

    <van-cell-group inset class="block">
      <van-cell v-if="selected" is-link @click="pickerVisible = true">
        <template #title>
          <div class="receiver">
            <span>{{ selected.receiver }}</span>
            <span class="muted">{{ selected.phone }}</span>
            <van-tag v-if="selected.isDefault === 1" type="danger" plain>默认</van-tag>
          </div>
          <div class="muted addr">{{ fullAddress(selected) }}</div>
        </template>
      </van-cell>
      <van-cell v-else title="还没有收货地址" is-link @click="router.push('/address/new')">
        <template #value>去添加</template>
      </van-cell>
    </van-cell-group>

    <van-cell-group inset class="block">
      <van-cell title="商品清单" :value="`共 ${items.length} 件`" />
      <van-card
        v-for="c in items"
        :key="c.id"
        :num="c.quantity"
        :price="money(c.price).replace('¥', '')"
        :title="c.productName"
        :desc="c.spec"
        :thumb="c.productImage || ''"
      />
    </van-cell-group>

    <van-cell-group inset class="block">
      <van-cell title="商品金额" :value="money(total)" />
      <van-cell title="运费" value="¥0.00" />
      <van-cell title="应付金额" :value="money(total)" class="payable" />
    </van-cell-group>

    <!-- loading 期间禁用：清单还没到就点提交，submit() 只会弹「没有待结算的商品」，
         白让用户以为下单失败（这个状态在慢网络下真的会出现） -->
    <van-submit-bar
      :price="Math.round(total * 100)"
      button-text="提交订单"
      :loading="submitting"
      :disabled="loading"
      @submit="submit"
    />

    <van-popup v-model:show="pickerVisible" position="bottom" round>
      <div class="pick-head">选择收货地址</div>
      <van-radio-group :model-value="addressId" @update:model-value="(v) => choose(Number(v))">
        <van-cell-group>
          <van-cell v-for="a in addresses" :key="a.id" :title="`${a.receiver} ${a.phone}`" :label="fullAddress(a)" clickable>
            <template #right-icon>
              <van-radio :name="a.id" />
            </template>
          </van-cell>
        </van-cell-group>
      </van-radio-group>
      <van-empty v-if="!addresses.length" description="没有地址" />
      <div class="pick-foot">
        <van-button round block type="danger" plain @click="router.push('/address/new')">新增地址</van-button>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.block {
  margin-top: 12px;
}

.receiver {
  display: flex;
  align-items: center;
  gap: 8px;
}

.addr {
  margin-top: 4px;
  white-space: normal;
}

.payable :deep(.van-cell__value) {
  color: #ee0a24;
  font-weight: 600;
}

.pick-head {
  padding: 16px;
  text-align: center;
  font-weight: 600;
}

.pick-foot {
  padding: 12px 16px 24px;
}
</style>
