<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast } from 'vant'
import { afterSaleApi } from '@/api'
import type { AfterSaleVO } from '@/types'
import { formatDateTime, money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const as = ref<AfterSaleVO | null>(null)
const loadFailed = ref(false)
const submitting = ref(false)

const headline = computed(() => {
  switch (as.value?.status) {
    case 0:
      return '商家正在处理'
    case 1:
      return '退款已完成'
    case 2:
      return '商家已拒绝'
    case 3:
      return '你已撤销申请'
    default:
      return ''
  }
})

const subline = computed(() => {
  switch (as.value?.status) {
    case 0:
      return '一般 48 小时内处理，处理结果会在这里更新'
    case 1:
      return as.value?.type === 2
        ? '货款已原路退回，退回的商品已入库'
        : '货款已原路退回'
    case 2:
      return as.value?.handleRemark || '如有疑问可联系客服'
    case 3:
      return '如仍需售后，可在订单详情里重新申请'
    default:
      return ''
  }
})

async function load() {
  const id = Number(route.params.id)
  try {
    as.value = await afterSaleApi.detail(id)
  } catch {
    loadFailed.value = true
  }
}

async function cancel() {
  if (!as.value) return
  try {
    await showConfirmDialog({ title: '撤销售后', message: '撤销后商家将不再处理本次申请，确定？' })
  } catch {
    return
  }
  submitting.value = true
  try {
    await afterSaleApi.cancel(as.value.id)
    showSuccessToast('已撤销')
    await load()
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="app-shell" :class="{ 'pb-submit': as?.status === 0 }">
    <van-nav-bar title="售后详情" left-arrow @click-left="router.back()" />

    <van-empty v-if="!as" :description="loadFailed ? '售后单不存在' : '加载中…'" />

    <template v-else>
      <div class="banner" :class="`s${as.status}`">
        <div class="headline">{{ headline }}</div>
        <div class="subline">{{ subline }}</div>
      </div>

      <!-- 时间线：Vant 的 Steps 没在这个项目里注册，用原生元素画，样式更好控 -->
      <div class="timeline">
        <div class="tl-item done">
          <div class="dot" />
          <div class="tl-body">
            <div class="tl-title">提交申请</div>
            <div class="tl-time">{{ formatDateTime(as.applyTime) }}</div>
            <div class="tl-desc">{{ as.typeText }} · {{ as.reason }}</div>
          </div>
        </div>

        <div class="tl-item" :class="{ done: as.status !== 0, fail: as.status === 2 || as.status === 3 }">
          <div class="dot" />
          <div class="tl-body">
            <div class="tl-title">
              {{ as.status === 0 ? '等待商家处理' : as.status === 3 ? '用户撤销' : '商家处理' }}
            </div>
            <div class="tl-time">{{ as.handleTime ? formatDateTime(as.handleTime) : '—' }}</div>
            <div v-if="as.handleRemark" class="tl-desc">{{ as.handleRemark }}</div>
          </div>
        </div>

        <div v-if="as.status === 1" class="tl-item done">
          <div class="dot" />
          <div class="tl-body">
            <div class="tl-title">退款完成</div>
            <div class="tl-time">{{ formatDateTime(as.handleTime) }}</div>
            <div class="tl-desc">退款金额 {{ money(as.refundAmount) }}，已原路退回</div>
          </div>
        </div>
      </div>

      <van-cell-group inset class="block">
        <van-cell title="售后单号" :value="as.afterSaleNo" />
        <van-cell title="订单编号" :value="as.orderNo" />
        <van-cell title="售后类型" :value="as.typeText" />
        <van-cell title="退款金额" :value="money(as.refundAmount)" class="amount" />
        <van-cell title="申请原因" :value="as.reason" />
        <van-cell v-if="as.description" title="补充说明" :value="as.description" />
      </van-cell-group>

      <van-cell-group v-if="as.items && as.items.length" inset class="block">
        <van-card
          v-for="it in as.items"
          :key="it.id"
          :num="it.quantity"
          :price="Number(it.price).toFixed(2)"
          :title="it.productName"
          :desc="it.spec"
          :thumb="it.productImage || ''"
        />
      </van-cell-group>

      <div v-if="as.status === 0" class="footer">
        <van-button round plain :loading="submitting" @click="cancel">撤销售后</van-button>
        <van-button type="danger" round @click="router.push('/orders')">查看订单</van-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pb-submit {
  padding-bottom: 64px;
}

.banner {
  margin: 12px;
  padding: 16px;
  border-radius: 10px;
  background: #fff7e6;
  color: #8a5a00;
}

.banner.s1 {
  background: #e8f7ee;
  color: #0a7a3d;
}

.banner.s2 {
  background: #fdecec;
  color: #c02626;
}

.banner.s3 {
  background: #f2f3f5;
  color: #646566;
}

.headline {
  font-size: 18px;
  font-weight: 600;
}

.subline {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  opacity: 0.9;
}

.timeline {
  margin: 4px 12px 0;
  padding: 12px 12px 4px;
  background: #fff;
  border-radius: 8px;
}

.tl-item {
  position: relative;
  padding-left: 20px;
  padding-bottom: 16px;
}

.tl-item::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 12px;
  bottom: 0;
  width: 2px;
  background: #ebedf0;
}

.tl-item:last-child::before {
  display: none;
}

.dot {
  position: absolute;
  left: 0;
  top: 4px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #dcdee0;
}

.tl-item.done .dot {
  background: #07c160;
}

.tl-item.fail .dot {
  background: #ee0a24;
}

.tl-title {
  font-size: 14px;
  color: #323233;
}

.tl-time {
  margin-top: 2px;
  font-size: 12px;
  color: #969799;
}

.tl-desc {
  margin-top: 4px;
  font-size: 12px;
  color: #646566;
  line-height: 1.5;
}

.block {
  margin-top: 12px;
}

.amount :deep(.van-cell__value) {
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
