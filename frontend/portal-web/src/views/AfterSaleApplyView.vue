<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showFailToast, showSuccessToast } from 'vant'
import { afterSaleApi, orderApi } from '@/api'
import { AFTER_SALE_REASONS, AFTER_SALE_TYPES, type OrderVO } from '@/types'
import { money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const order = ref<OrderVO | null>(null)
const loadFailed = ref(false)
const type = ref(1)
const reason = ref('')
const description = ref('')
const submitting = ref(false)

const currentType = computed(() => AFTER_SALE_TYPES.find((t) => t.value === type.value))
const refundAmount = computed(() => Number(order.value?.payAmount || 0))

async function load() {
  const id = Number(route.query.orderId)
  if (!id) {
    loadFailed.value = true
    return
  }
  try {
    order.value = await orderApi.detail(id)
  } catch {
    // 业务失败（订单不存在/不是自己的）不应该冒到全局错误边界，页面里给空状态
    loadFailed.value = true
  }
}

async function submit() {
  if (!order.value) return
  if (!reason.value) {
    showFailToast('请选择售后原因')
    return
  }
  submitting.value = true
  try {
    const id = await afterSaleApi.apply({
      orderId: order.value.id,
      type: type.value,
      reason: reason.value,
      description: description.value.trim() || undefined
    })
    showSuccessToast('已提交，等待商家处理')
    router.replace(`/after-sale/${id}`)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="app-shell pb-submit">
    <van-nav-bar title="申请售后" left-arrow @click-left="router.back()" />

    <van-empty v-if="!order" :description="loadFailed ? '订单不存在或不可申请售后' : '加载中…'" />

    <template v-else>
      <div class="notice">
        提交后商家会在 48 小时内处理。可申请的订单状态：已支付 / 已发货 / 已完成。
      </div>

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
        <van-cell title="订单编号" :value="order.orderNo" />
        <van-cell title="订单状态" :value="order.statusText" />
      </van-cell-group>

      <div class="section-title">退款方式</div>
      <van-cell-group inset>
        <van-radio-group v-model="type">
          <van-cell v-for="t in AFTER_SALE_TYPES" :key="t.value" clickable @click="type = t.value">
            <template #title>
              <div class="type-label">{{ t.label }}</div>
              <div class="type-hint">{{ t.hint }}</div>
            </template>
            <template #right-icon>
              <van-radio :name="t.value" />
            </template>
          </van-cell>
        </van-radio-group>
      </van-cell-group>

      <div class="section-title">退款原因</div>
      <van-cell-group inset>
        <van-radio-group v-model="reason">
          <van-cell v-for="r in AFTER_SALE_REASONS" :key="r" :title="r" clickable @click="reason = r">
            <template #right-icon>
              <van-radio :name="r" />
            </template>
          </van-cell>
        </van-radio-group>
      </van-cell-group>

      <div class="section-title">补充说明（选填）</div>
      <van-cell-group inset>
        <van-field
          v-model="description"
          type="textarea"
          rows="3"
          maxlength="500"
          show-word-limit
          placeholder="可以补充具体情况，便于商家更快处理"
        />
      </van-cell-group>

      <div class="tips">
        <div v-if="currentType?.value === 1">仅退款：商家同意后直接退款，商品无需寄回。</div>
        <div v-else>退货退款：商家同意后请把商品寄回，商家收到货后会退款并恢复库存。</div>
        <div>退款金额为订单实付金额，审核通过后原路退回。</div>
      </div>

      <van-submit-bar :price="Math.round(refundAmount * 100)" button-text="提交申请" :loading="submitting" @submit="submit">
        <template #tip>
          <span class="muted">退款金额：{{ money(refundAmount) }}</span>
        </template>
      </van-submit-bar>
    </template>
  </div>
</template>

<style scoped>
.pb-submit {
  padding-bottom: 60px;
}

.block {
  margin-top: 12px;
}

.notice {
  margin: 12px;
  padding: 10px 12px;
  background: #fff8e6;
  color: #8a6d3b;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.6;
}

.section-title {
  padding: 14px 16px 6px;
  font-size: 13px;
  color: #969799;
}

.type-label {
  font-size: 14px;
  color: #323233;
}

.type-hint {
  margin-top: 2px;
  font-size: 12px;
  color: #969799;
  line-height: 1.5;
}

.tips {
  margin: 16px 16px 8px;
  font-size: 12px;
  color: #969799;
  line-height: 1.8;
}
</style>
