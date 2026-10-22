<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast } from 'vant'
import { afterSaleApi, orderApi } from '@/api'
import type { AfterSaleVO, OrderVO } from '@/types'
import { formatDateTime, money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const order = ref<OrderVO | null>(null)
const afterSale = ref<AfterSaleVO | null>(null)
const submitting = ref(false)
/** 加载失败（最常见的是 3001 订单不存在）。拦截器已经弹过提示，页面只需显示一个可读的空状态 */
const loadFailed = ref(false)

/** 已支付 / 已发货 / 已完成 且没有售后单时才能申请（和后台的校验一致） */
const canApply = computed(() => !!order.value && [1, 2, 3].includes(order.value.status) && !afterSale.value)

async function load() {
  const raw = route.params.id
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  try {
    order.value = await orderApi.detail(id)
    loadFailed.value = false
    // 查一次最近一条售后单：有就显示入口，没有返回 null（不是错误）
    afterSale.value = await afterSaleApi.byOrder(id).catch(() => null)
  } catch {
    // ⚠️ 别把这个异常放出去：onMounted 的回调被 Vue 用 callWithAsyncErrorHandling 包过，
    // reject 会一路冒到全局错误边界，把整页换成「页面出错了」——
    // 而「订单不存在」是正常业务结果，应该在页面里显示空状态
    loadFailed.value = true
  }
}

/** 去支付：跳收银台（渠道选择 + 支付结果都在那边） */
function pay() {
  if (!order.value) return
  router.push(`/pay/${order.value.id}`)
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
  <div
    class="app-shell"
    :class="{ 'pb-submit': order && (order.status === 0 || order.status === 2 || canApply || afterSale) }"
  >
    <van-nav-bar title="订单详情" left-arrow @click-left="router.back()" />

    <van-empty v-if="!order" :description="loadFailed ? '订单不存在或已被删除' : '加载中…'" />

    <template v-if="order">
      <van-cell-group inset class="block status-block">
        <div class="status">{{ order.statusText }}</div>
        <div v-if="order.status === 0" class="muted">请在 {{ formatDateTime(order.expireTime) }} 前完成支付，超时自动关单</div>
      </van-cell-group>

      <!-- 售后入口：有售后单就直接显示当前进度，一眼能看到「走到哪一步了」 -->
      <div v-if="afterSale" class="as-banner" @click="router.push(`/after-sale/${afterSale.id}`)">
        <div class="as-head">
          <span class="as-title">售后 · {{ afterSale.statusText }}</span>
          <span class="as-link">查看详情 ›</span>
        </div>
        <div class="as-sub">
          {{ afterSale.typeText }} · 退款 {{ money(afterSale.refundAmount) }}
          <span v-if="afterSale.handleRemark" class="as-remark">｜{{ afterSale.handleRemark }}</span>
        </div>
      </div>

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

      <div v-if="order.status === 0 || order.status === 2 || canApply || afterSale" class="footer">
        <van-button v-if="afterSale" round plain @click="router.push(`/after-sale/${afterSale.id}`)">查看售后</van-button>
        <van-button
          v-else-if="canApply"
          round
          plain
          @click="router.push(`/after-sale/new?orderId=${order.id}`)"
        >
          申请售后
        </van-button>
        <van-button v-if="order.status === 0" round plain @click="cancel">取消订单</van-button>
        <van-button
          v-if="order.status === 0 || order.status === 2"
          type="danger"
          round
          :loading="submitting"
          @click="order.status === 0 ? pay() : confirm()"
        >
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

.as-banner {
  margin: 12px 16px 0;
  padding: 12px;
  border-radius: 8px;
  background: #fff7e6;
}

.as-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.as-title {
  font-size: 14px;
  font-weight: 600;
  color: #8a5a00;
}

.as-link {
  font-size: 12px;
  color: #969799;
}

.as-sub {
  margin-top: 6px;
  font-size: 12px;
  color: #646566;
  line-height: 1.5;
}

.as-remark {
  color: #969799;
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
