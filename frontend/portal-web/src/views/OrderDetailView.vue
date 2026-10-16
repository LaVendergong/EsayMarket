<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast } from 'vant'
import { orderApi } from '@/api'
import type { OrderVO } from '@/types'
import { formatDateTime, money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const order = ref<OrderVO | null>(null)
const submitting = ref(false)

async function load() {
  const raw = route.params.id
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  order.value = await orderApi.detail(id)
}

async function pay() {
  if (!order.value) return
  submitting.value = true
  try {
    await orderApi.pay(order.value.id)
    showSuccessToast('支付成功')
    await load()
  } finally {
    submitting.value = false
  }
}

async function cancel() {
  if (!order.value) return
  try {
    await showConfirmDialog({ title: '取消订单', message: '取消后会释放该订单锁定的库存，确定？' })
  } catch {
    return
  }
  await orderApi.cancel(order.value.id)
  showSuccessToast('已取消')
  await load()
}

async function confirm() {
  if (!order.value) return
  try {
    await showConfirmDialog({ title: '确认收货', message: '确认已收到货？' })
  } catch {
    return
  }
  await orderApi.confirm(order.value.id)
  showSuccessToast('交易完成')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="app-shell" :class="{ 'pb-submit': order && (order.status === 0 || order.status === 2) }">
    <van-nav-bar title="订单详情" left-arrow @click-left="router.back()" />

    <template v-if="order">
      <van-cell-group inset class="block status-block">
        <div class="status">{{ order.statusText }}</div>
        <div v-if="order.status === 0" class="muted">请在 {{ formatDateTime(order.expireTime) }} 前完成支付，超时自动关单</div>
      </van-cell-group>

      <van-cell-group inset class="block">
        <van-cell title="收货人" :value="order.receiver || '-'" />
        <van-cell title="联系电话" :value="order.receiverPhone || '-'" />
        <van-cell title="收货地址">
          <template #value>
            <span class="addr">{{ order.receiverAddr || '-' }}</span>
          </template>
        </van-cell>
      </van-cell-group>

      <van-cell-group inset class="block">
        <van-card
          v-for="it in order.items"
          :key="it.id"
          :num="it.quantity"
          :price="Number(it.price).toFixed(2)"
          :title="it.productName"
          :desc="it.spec"
          :thumb="it.productImage || ''"
        />
      </van-cell-group>

      <van-cell-group inset class="block">
        <van-cell title="订单编号" :value="order.orderNo" />
        <van-cell title="下单时间" :value="formatDateTime(order.createTime)" />
        <van-cell title="支付时间" :value="formatDateTime(order.payTime)" />
        <van-cell title="商品金额" :value="money(order.totalAmount)" />
        <van-cell title="实付金额" :value="money(order.payAmount)" class="payable" />
      </van-cell-group>

      <div v-if="order.status === 0 || order.status === 2" class="footer">
        <van-button v-if="order.status === 0" round plain @click="cancel">取消订单</van-button>
        <van-button type="danger" round :loading="submitting" @click="order.status === 0 ? pay() : confirm()">
          {{ order.status === 0 ? '去支付' : '确认收货' }}
        </van-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.block {
  margin-top: 12px;
}

.status-block {
  text-align: center;
}

.status {
  font-size: 18px;
  font-weight: 600;
  color: #ee0a24;
  padding-bottom: 6px;
}

.addr {
  white-space: normal;
  text-align: right;
}

.payable :deep(.van-cell__value) {
  color: #ee0a24;
  font-weight: 600;
}

.footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  max-width: 640px;
  margin: 0 auto;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 10px 16px;
  background: #fff;
  border-top: 1px solid #ebedf0;
}
</style>
