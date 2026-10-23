<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import { couponApi } from '@/api'
import type { CouponVO, UserCouponVO } from '@/types'

const router = useRouter()
const tab = ref(0)
const mine = ref<UserCouponVO[]>([])
const center = ref<CouponVO[]>([])

/** 未使用的排前面，剩下的按过期时间倒序（后端按 id 倒序返回） */
const sortedMine = computed(() =>
  [...mine.value].sort((a, b) => {
    if (a.status !== b.status) return a.status - b.status
    return b.id - a.id
  })
)

function fmt(v?: string | null) {
  return v ? v.replace('T', ' ').slice(0, 10) : '长期有效'
}

async function loadMine() {
  mine.value = await couponApi.mine()
}

async function loadCenter() {
  center.value = await couponApi.center()
}

async function load() {
  await Promise.all([loadMine(), loadCenter()])
}

async function receive(c: CouponVO) {
  try {
    await couponApi.receive(c.id)
    showSuccessToast('领取成功')
    await load()
  } catch {
    // 拦截器已提示（已领过 / 已领完 / 已停发）
  }
}

function gotoUse() {
  showToast('去购物车挑商品吧')
  router.push('/cart')
}

onMounted(load)
</script>

<template>
  <div class="app-shell">
    <van-nav-bar title="优惠券" left-arrow @click-left="router.back()" />

    <van-tabs v-model:active="tab" sticky>
      <van-tab title="我的券">
        <div v-if="!sortedMine.length" class="empty">
          <van-empty description="还没有优惠券，去领券中心看看">
            <van-button round type="danger" size="small" @click="tab = 1">去领券</van-button>
          </van-empty>
        </div>
        <div v-else class="list">
          <div v-for="c in sortedMine" :key="c.id" class="coupon" :class="{ dim: c.status !== 0 }">
            <div class="amount">
              <span class="num">¥{{ c.amount }}</span>
              <span class="cond">满 {{ c.threshold }} 可用</span>
            </div>
            <div class="info">
              <div class="name">{{ c.name }}</div>
              <div class="muted">{{ fmt(c.validFrom) }} ~ {{ fmt(c.validTo) }}</div>
              <div class="muted">{{ c.statusText || (c.status === 0 ? '未使用' : c.status === 1 ? '已使用' : '已过期') }}</div>
            </div>
            <van-button
              v-if="c.status === 0"
              size="small"
              type="danger"
              round
              class="use"
              @click="gotoUse"
            >
              去使用
            </van-button>
          </div>
        </div>
      </van-tab>

      <van-tab title="领券中心">
        <div v-if="!center.length" class="empty">
          <van-empty description="暂时没有可领的券" />
        </div>
        <div v-else class="list">
          <div v-for="c in center" :key="c.id" class="coupon" :class="{ dim: c.receivedByMe }">
            <div class="amount">
              <span class="num">¥{{ c.amount }}</span>
              <span class="cond">满 {{ c.threshold }} 可用</span>
            </div>
            <div class="info">
              <div class="name">{{ c.name }}</div>
              <div class="muted">{{ fmt(c.validFrom) }} ~ {{ fmt(c.validTo) }}</div>
              <div class="muted">已领 {{ c.received }} / {{ c.total }}</div>
            </div>
            <van-button
              size="small"
              round
              :type="c.receivedByMe ? 'default' : 'danger'"
              :disabled="c.receivedByMe"
              class="use"
              @click="receive(c)"
            >
              {{ c.receivedByMe ? '已领取' : '领取' }}
            </van-button>
          </div>
        </div>
      </van-tab>
    </van-tabs>
  </div>
</template>

<style scoped>
.list {
  padding: 12px;
}

.coupon {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #fff;
  border-radius: 10px;
  padding: 14px;
  margin-bottom: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.coupon.dim {
  opacity: 0.55;
}

.amount {
  width: 96px;
  flex: none;
  text-align: center;
  color: #ee0a24;
  border-right: 1px dashed #eee;
}

.amount .num {
  display: block;
  font-size: 22px;
  font-weight: 700;
}

.amount .cond {
  font-size: 11px;
  color: #969799;
}

.info {
  flex: 1;
  min-width: 0;
}

.name {
  font-weight: 600;
  margin-bottom: 4px;
}

.use {
  flex: none;
}

.muted {
  color: #969799;
  font-size: 12px;
  line-height: 1.6;
}

.empty {
  padding-top: 24px;
}
</style>
