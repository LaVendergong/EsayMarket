<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { dashboardApi } from '@/api'
import type { DashboardSummaryVO, LowStockVO } from '@/types'
import { money } from '@/utils/format'

const router = useRouter()
const summary = ref<DashboardSummaryVO | null>(null)
const lowStock = ref<LowStockVO[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const [s, l] = await Promise.all([dashboardApi.summary(), dashboardApi.lowStock(10)])
    summary.value = s
    lowStock.value = l
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <el-row :gutter="12">
      <el-col :span="12">
        <el-card shadow="never" class="stat">
          <div class="label">今日 GMV</div>
          <div class="value primary">{{ money(summary?.todayGmv || 0) }}</div>
          <div class="hint">今日成交 {{ summary?.todayOrders ?? 0 }} 单</div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" class="stat">
          <div class="label">今日待办</div>
          <div class="todo">
            <div class="todo-item" @click="router.push('/order?status=1')">
              <span class="num">{{ summary?.pendingShip ?? 0 }}</span>
              <span class="txt">待发货</span>
            </div>
            <div class="todo-item" @click="router.push('/after-sale')">
              <span class="num">{{ summary?.pendingAfterSale ?? 0 }}</span>
              <span class="txt">待处理售后</span>
            </div>
            <div class="todo-item" @click="router.push('/order?status=1')">
              <span class="num">{{ summary?.pendingGrant ?? 0 }}</span>
              <span class="txt">待补发凭证</span>
            </div>
            <div class="todo-item" @click="router.push('/product')">
              <span class="num">{{ summary?.lowStockCount ?? 0 }}</span>
              <span class="txt">低库存 SKU</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header>
        <span>低库存提醒</span>
        <span class="muted">（可售 ≤ {{ summary?.lowStockThreshold ?? 0 }} 的 SKU，虚拟卡密商品为 0 时请去导入卡密）</span>
      </template>
      <el-table :data="lowStock" empty-text="库存都还健康">
        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">
            <el-link type="primary" @click="router.push(`/product/${row.productId}`)">{{ row.productName }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="spec" label="规格" width="140" />
        <el-table-column prop="stock" label="库存" width="100" />
        <el-table-column prop="lockedStock" label="已锁定" width="100" />
        <el-table-column label="可售" width="100">
          <template #default="{ row }">
            <el-tag :type="row.available <= 0 ? 'danger' : 'warning'">{{ row.available }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/product/${row.productId}/edit`)">去补货</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.stat .label {
  color: #909399;
  font-size: 13px;
  margin-bottom: 8px;
}

.stat .value {
  font-size: 28px;
  font-weight: 600;
}

.stat .value.primary {
  color: #ee0a24;
}

.stat .hint {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}

.todo {
  display: flex;
  gap: 12px;
}

.todo-item {
  flex: 1;
  text-align: center;
  padding: 8px 4px;
  border-radius: 6px;
  background: #fafafa;
  cursor: pointer;
}

.todo-item:hover {
  background: #f2f6fc;
}

.todo-item .num {
  display: block;
  font-size: 22px;
  font-weight: 600;
}

.todo-item .txt {
  color: #909399;
  font-size: 12px;
}

.block {
  margin-top: 12px;
}

.muted {
  color: #909399;
  font-size: 12px;
}
</style>
