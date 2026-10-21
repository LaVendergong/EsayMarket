<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { afterSaleApi } from '@/api'
import type { AfterSaleVO } from '@/types'
import { formatDateTime, money } from '@/utils/format'

const router = useRouter()

const list = ref<AfterSaleVO[]>([])
const pageNum = ref(1)
const pageSize = 10
const loading = ref(false)
const finished = ref(false)
const refreshing = ref(false)

const STATUS_TAG: Record<number, { type: 'warning' | 'success' | 'danger' | 'default'; label: string }> = {
  0: { type: 'warning', label: '待商家处理' },
  1: { type: 'success', label: '已退款' },
  2: { type: 'danger', label: '商家已拒绝' },
  3: { type: 'default', label: '已撤销' }
}

async function loadPage() {
  loading.value = true
  try {
    const page = await afterSaleApi.page(pageNum.value, pageSize)
    list.value = pageNum.value === 1 ? page.list : [...list.value, ...page.list]
    finished.value = list.value.length >= page.total || page.list.length === 0
    if (!finished.value) pageNum.value += 1
  } catch {
    finished.value = true
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

function reload() {
  pageNum.value = 1
  list.value = []
  finished.value = false
  loadPage()
}

onMounted(reload)
</script>

<template>
  <div class="app-shell">
    <van-nav-bar title="我的售后" left-arrow @click-left="router.back()" />

    <van-pull-refresh v-model="refreshing" @refresh="reload">
      <van-list
        v-model:loading="loading"
        :finished="finished"
        finished-text="没有更多了"
        :immediate-check="false"
        @load="loadPage"
      >
        <div v-for="a in list" :key="a.id" class="card" @click="router.push(`/after-sale/${a.id}`)">
          <div class="head">
            <span class="no">{{ a.afterSaleNo }}</span>
            <van-tag :type="STATUS_TAG[a.status]?.type || 'default'">
              {{ STATUS_TAG[a.status]?.label || a.statusText }}
            </van-tag>
          </div>
          <div class="row">
            <span class="muted">{{ a.typeText }}</span>
            <span class="amount">退款 {{ money(a.refundAmount) }}</span>
          </div>
          <div class="row">
            <span class="muted">原因：{{ a.reason }}</span>
            <span class="muted">{{ formatDateTime(a.applyTime) }}</span>
          </div>
          <div v-if="a.handleRemark" class="remark">商家：{{ a.handleRemark }}</div>
        </div>
      </van-list>

      <van-empty v-if="finished && list.length === 0" description="还没有售后记录">
        <van-button round type="danger" class="go" @click="router.push('/orders')">查看我的订单</van-button>
      </van-empty>
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.card {
  background: #fff;
  margin: 10px 12px;
  border-radius: 8px;
  padding: 12px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid #f2f3f5;
}

.no {
  font-size: 12px;
  color: #323233;
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  font-size: 13px;
}

.amount {
  color: #ee0a24;
  font-weight: 600;
}

.remark {
  margin-top: 8px;
  padding: 6px 8px;
  background: #f7f8fa;
  border-radius: 6px;
  font-size: 12px;
  color: #646566;
}

.go {
  width: 150px;
}
</style>
