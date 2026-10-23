<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { maintenanceApi } from '@/api'
import type { MaintenanceVO } from '@/types'

const data = ref<MaintenanceVO | null>(null)
const loading = ref(false)

function fmt(v?: string) {
  return v ? v.replace('T', ' ').slice(0, 19) : '-'
}

async function load() {
  loading.value = true
  try {
    data.value = await maintenanceApi.stats()
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <el-row :gutter="12" class="cards">
      <el-col :span="8">
        <el-card shadow="never" class="stat">
          <div class="label">搜索日志保留</div>
          <div class="value">{{ data?.searchLogRetainDays ?? '-' }} 天</div>
          <div class="hint">每天 03:30 自动清理，过期数据只占存储</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat">
          <div class="label">最近一次清理</div>
          <div class="value small">{{ fmt(data?.searchLogLastCleanAt) }}</div>
          <div class="hint">应用重启后显示为空（进程内记录，不为此建表）</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat">
          <div class="label">上次清理行数</div>
          <div class="value">{{ data?.searchLogLastDeleted ?? '-' }}</div>
          <div class="hint">t_search_log 累计去掉的行数</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header>
        <span>数据量</span>
        <span class="muted">（只统计会持续增长的业务表，行数为精确值）</span>
      </template>
      <el-table :data="data?.tables ?? []">
        <el-table-column prop="name" label="表" min-width="200" />
        <el-table-column prop="rowCount" label="行数" width="160">
          <template #default="{ row }">{{ row.rowCount.toLocaleString() }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-alert
      class="tip"
      type="info"
      :closable="false"
      show-icon
      title="增长最快的通常是 t_search_log 和 t_order_item。前者有自动清理，后者随订单线性增长，导出/归档请走订单导出。"
    />
  </div>
</template>

<style scoped>
.cards {
  margin-bottom: 12px;
}

.stat .label {
  color: #909399;
  font-size: 13px;
  margin-bottom: 8px;
}

.stat .value {
  font-size: 24px;
  font-weight: 600;
}

.stat .value.small {
  font-size: 18px;
}

.stat .hint {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}

.muted {
  color: #909399;
  font-size: 12px;
}

.tip {
  margin-top: 12px;
}
</style>
