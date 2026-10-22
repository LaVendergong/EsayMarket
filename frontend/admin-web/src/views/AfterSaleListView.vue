<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { afterSaleApi } from '@/api'
import type { AfterSaleVO } from '@/types'

const list = ref<AfterSaleVO[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 10
const status = ref<number | ''>('')
const loading = ref(false)

const STATUS_OPTIONS = [
  { value: '', label: '全部' },
  { value: 0, label: '待处理' },
  { value: 1, label: '已退款' },
  { value: 2, label: '已拒绝' },
  { value: 3, label: '已撤销' }
]

const STATUS_TAG: Record<number, { type: 'warning' | 'success' | 'danger' | 'info'; label: string }> = {
  0: { type: 'warning', label: '待处理' },
  1: { type: 'success', label: '已退款' },
  2: { type: 'danger', label: '已拒绝' },
  3: { type: 'info', label: '已撤销' }
}

async function load() {
  loading.value = true
  try {
    const page = await afterSaleApi.page({
      pageNum: pageNum.value,
      pageSize,
      status: status.value === '' ? undefined : Number(status.value)
    })
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function onSearch() {
  pageNum.value = 1
  load()
}

/** 同意 = 退款（退货退款还会把库存加回去），要二次确认，因为不可逆 */
async function approve(row: AfterSaleVO) {
  let remark = ''
  try {
    const result = await ElMessageBox.prompt(
      `确认为「${row.orderNo}」退款 ${row.refundAmount} 元？${row.type === 2 ? '退货退款：库存会加回去。' : '仅退款：库存不变。'}`,
      '同意售后',
      { confirmButtonText: '确认退款', cancelButtonText: '取消', inputPlaceholder: '处理备注（选填）' }
    )
    remark = result.value || ''
  } catch {
    return
  }
  await afterSaleApi.approve(row.id, remark)
  ElMessage.success('已同意并退款')
  load()
}

async function reject(row: AfterSaleVO) {
  let remark = ''
  try {
    const result = await ElMessageBox.prompt('拒绝后订单会退回申请前的状态，请填写拒绝理由', '拒绝售后', {
      confirmButtonText: '确认拒绝',
      cancelButtonText: '取消',
      inputPlaceholder: '拒绝理由（会展示给用户）'
    })
    remark = result.value || ''
  } catch {
    return
  }
  await afterSaleApi.reject(row.id, remark)
  ElMessage.success('已拒绝')
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="bar">
      <el-radio-group v-model="status" @change="onSearch">
        <el-radio-button v-for="o in STATUS_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</el-radio-button>
      </el-radio-group>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column prop="afterSaleNo" label="售后单号" width="190" />
      <el-table-column prop="orderNo" label="订单号" width="190" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ row.typeText }}</template>
      </el-table-column>
      <el-table-column label="退款金额" width="120">
        <template #default="{ row }">¥{{ Number(row.refundAmount).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="140" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status]?.type || 'info'">{{ STATUS_TAG[row.status]?.label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="退款" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.refundStatus === 3" type="danger">退款失败</el-tag>
          <el-tag v-else-if="row.refundStatus === 2" type="success">已退款</el-tag>
          <el-tag v-else-if="row.refundStatus === 1" type="warning">退款中</el-tag>
          <span v-else class="muted">未退款</span>
        </template>
      </el-table-column>
      <el-table-column prop="applyTime" label="申请时间" width="180" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 0">
            <el-button link type="success" @click="approve(row)">同意退款</el-button>
            <el-button link type="danger" @click="reject(row)">拒绝</el-button>
          </template>
          <span v-else class="muted">{{ row.handleRemark || '已处理' }}</span>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="pageNum"
      @current-change="(p: number) => { pageNum = p; load() }"
    />
  </div>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.muted {
  color: #909399;
  font-size: 12px;
}
</style>
