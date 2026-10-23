<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { auditApi } from '@/api'
import type { AdminAuditLogVO } from '@/types'

const list = ref<AdminAuditLogVO[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  pageNum: 1,
  pageSize: 20,
  action: undefined as string | undefined
})

const actions = [
  { value: 'PRODUCT_UPDATE', label: '修改商品' },
  { value: 'PRODUCT_DELETE', label: '删除商品' },
  { value: 'ORDER_SHIP', label: '订单发货' },
  { value: 'ORDER_CLOSE', label: '关闭订单' },
  { value: 'ORDER_GRANT', label: '补发凭证' },
  { value: 'AFTER_SALE_APPROVE', label: '同意售后' },
  { value: 'AFTER_SALE_REJECT', label: '拒绝售后' },
  { value: 'REVIEW_HIDE', label: '隐藏评价' },
  { value: 'REVIEW_RESTORE', label: '恢复评价' },
  { value: 'CARD_KEY_IMPORT', label: '导入卡密' }
]

function fmt(v?: string) {
  return v ? v.replace('T', ' ').slice(0, 19) : '-'
}

async function load() {
  loading.value = true
  try {
    const p = await auditApi.page({ pageNum: query.pageNum, pageSize: query.pageSize, action: query.action })
    list.value = p.list
    total.value = p.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  load()
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-select v-model="query.action" placeholder="全部动作" clearable style="width: 180px" @change="search">
        <el-option v-for="a in actions" :key="a.value" :label="a.label" :value="a.value" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" empty-text="还没有后台操作记录">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ fmt(row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="adminName" label="操作人" width="140" />
        <el-table-column label="动作" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.action.includes('APPROVE') || row.action.includes('SHIP') ? 'warning' : 'info'">
              {{ row.actionText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象" width="160">
          <template #default="{ row }">
            <span v-if="row.targetType">{{ row.targetType }}#{{ row.targetId }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" min-width="240">
          <template #default="{ row }">
            <span :class="{ muted: !row.detail }">{{ row.detail || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="140" />
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.spacer {
  flex: 1;
}

.muted {
  color: #c0c4cc;
}
</style>
