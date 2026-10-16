<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { orderApi } from '@/api'
import { ORDER_STATUS, type OrderVO } from '@/types'
import { formatDateTime } from '@/utils/format'

const router = useRouter()

const list = ref<OrderVO[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  status: undefined as number | undefined,
  keyword: ''
})

function statusTag(status: number) {
  return ORDER_STATUS.find((s) => s.value === status)?.tag ?? 'info'
}

async function load() {
  loading.value = true
  try {
    const page = await orderApi.page({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status,
      keyword: query.keyword || undefined
    })
    list.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  load()
}

function reset() {
  query.status = undefined
  query.keyword = ''
  search()
}

async function ship(row: OrderVO) {
  try {
    await ElMessageBox.confirm(`确定对订单「${row.orderNo}」发货？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await orderApi.ship(row.id)
    ElMessage.success('已发货')
    await load()
  } catch {
    // 拦截器已提示（只有已支付订单能发货，否则 3002）
  }
}

async function close(row: OrderVO) {
  try {
    await ElMessageBox.confirm(`确定关闭订单「${row.orderNo}」？会释放其锁定库存。`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await orderApi.close(row.id)
    ElMessage.success('已关闭')
    await load()
  } catch {
    // 拦截器已提示（只有待支付订单能关闭，否则 3002）
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 150px">
        <el-option v-for="s in ORDER_STATUS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>
      <el-input
        v-model="query.keyword"
        placeholder="按订单号搜索"
        clearable
        style="width: 220px"
        @keyup.enter="search"
      />
      <el-button type="primary" :icon="Search" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="reset">重置</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="orderNo" label="订单号" min-width="190" />
        <el-table-column prop="userId" label="用户 ID" width="90" />
        <el-table-column label="实付" width="110">
          <template #default="{ row }">¥{{ Number(row.payAmount).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="收货人" min-width="160">
          <template #default="{ row }">
            <div>{{ row.receiver || '-' }}</div>
            <div class="muted">{{ row.receiverPhone || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="下单时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/order/${row.id}`)">详情</el-button>
            <el-button v-if="row.status === 1" link type="success" @click="ship(row)">发货</el-button>
            <el-button v-if="row.status === 0" link type="danger" @click="close(row)">关闭</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="search"
        />
      </div>
    </el-card>
  </div>
</template>
