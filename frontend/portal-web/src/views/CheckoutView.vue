<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import { addressApi, cartApi, orderApi } from '@/api'
import { cart } from '@/stores/cart'
import type { AddressVO, CartVO } from '@/types'
import { money } from '@/utils/format'

const router = useRouter()

const addresses = ref<AddressVO[]>([])
const addressId = ref<number | undefined>(undefined)
const items = ref<CartVO[]>([])
const pickerVisible = ref(false)
const submitting = ref(false)

const selected = computed(() => addresses.value.find((a) => a.id === addressId.value))
const total = computed(() => items.value.reduce((s, c) => s + Number(c.totalPrice || 0), 0))
const fullAddress = (a: AddressVO) => [a.province, a.city, a.district, a.detail].filter(Boolean).join('')

async function load() {
  const [addrList, cartList] = await Promise.all([addressApi.list(), cartApi.list()])
  addresses.value = addrList
  addressId.value = (addrList.find((a) => a.isDefault === 1) ?? addrList[0])?.id
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
    const vo = await orderApi.create(addressId.value)
    await cart.refresh()
    showSuccessToast('下单成功')
    router.replace(`/order/${vo.orderId}`)
  } catch {
    // 拦截器已提示（没有默认地址 -> 2010，购物车为空 -> 2004）
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

    <van-submit-bar :price="Math.round(total * 100)" button-text="提交订单" :loading="submitting" @submit="submit" />

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
