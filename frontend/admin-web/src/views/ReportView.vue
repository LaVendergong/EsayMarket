<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { reportApi } from '@/api'
import type { DailyStatVO, ReportSummaryVO, TopProductVO } from '@/types'
import { money } from '@/utils/format'

const days = ref(7)
const loading = ref(false)
const summary = ref<ReportSummaryVO | null>(null)
const daily = ref<DailyStatVO[]>([])
const top = ref<TopProductVO[]>([])

async function load() {
  loading.value = true
  try {
    const [s, d, t] = await Promise.all([
      reportApi.summary(days.value),
      reportApi.daily(days.value),
      reportApi.topProducts(days.value, 10)
    ])
    summary.value = s
    daily.value = d
    top.value = t
  } finally {
    loading.value = false
  }
}

function changeDays(v: number) {
  days.value = v
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-radio-group :model-value="days" @change="(v: string | number | boolean) => changeDays(Number(v))">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
    </div>

    <el-row v-loading="loading" :gutter="12" class="cards">
      <el-col :span="6">
        <el-card shadow="never" class="stat">
          <div class="label">GMV（已支付）</div>
          <div class="value primary">{{ money(summary?.gmv || 0) }}</div>
          <div class="hint">退款 {{ money(summary?.refundAmount || 0) }}｜净收入 {{ money(summary?.netGmv || 0) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat">
          <div class="label">订单数</div>
          <div class="value">{{ summary?.orderCount ?? 0 }}</div>
          <div class="hint">下单人数 {{ summary?.buyerCount ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat">
          <div class="label">客单价</div>
          <div class="value">{{ money(summary?.avgOrderAmount || 0) }}</div>
          <div class="hint">新注册 {{ summary?.newUsers ?? 0 }} 人</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat">
          <div class="label">券核销率</div>
          <div class="value">{{ summary?.couponUseRate ?? 0 }}%</div>
          <div class="hint">发放 {{ summary?.couponReceived ?? 0 }}｜使用 {{ summary?.couponUsed ?? 0 }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header>按天统计</template>
      <el-table :data="daily" empty-text="区间内没有已支付订单">
        <el-table-column prop="statDate" label="日期" width="140" />
        <el-table-column prop="orderCount" label="订单数" width="120" />
        <el-table-column label="GMV" min-width="140">
          <template #default="{ row }">{{ money(row.gmv) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>热销商品 Top 10</template>
      <el-table :data="top" empty-text="区间内没有销量">
        <el-table-column type="index" label="#" width="60" />
        <el-table-column prop="productName" label="商品" min-width="220" />
        <el-table-column prop="quantity" label="件数" width="100" />
        <el-table-column label="实付金额" width="140">
          <template #default="{ row }">{{ money(row.amount) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
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

.stat .value.primary {
  color: #ee0a24;
}

.stat .hint {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}

.block {
  margin-bottom: 12px;
}

.spacer {
  flex: 1;
}
</style>
