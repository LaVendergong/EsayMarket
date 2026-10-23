<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import { orderApi } from '@/api'
import { ORDER_STATUS, type OrderVO } from '@/types'
import { formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const list = ref<OrderVO[]>([])
const total = ref(0)
const loading = ref(false)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  // 支持从看板点进来直接带筛选：/order?status=1（待发货）
  status: (route.query.status ? Number(route.query.status) : undefined) as number | undefined,
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

/** 导出当前筛选条件下的订单（最多 5000 行），交给浏览器下载 */
const exporting = ref(false)
async function exportCsv() {
  exporting.value = true
  try {
    const blob = await orderApi.exportCsv({ status: query.status, keyword: query.keyword || undefined })
    // 后端异常时返回的是 JSON 而不是 CSV，这里挡一下，别下载出一个内容是报错信息的文件
    if (!(blob instanceof Blob) || blob.type.includes('json')) {
      ElMessage.error('导出失败，请稍后重试')
      return
    }
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `orders-${new Date().toISOString().slice(0, 10)}.csv`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出')
  } catch {
    // 拦截器已提示
  } finally {
    exporting.value = false
  }
}

function reset() {
  query.status = undefined
  query.keyword = ''
  search()
}

async function ship(row: OrderVO) {
  // 发货必须填快递公司 + 运单号，列表里没有输入框，带到详情页去发
  ElMessage.info('请填写快递公司与运单号')
  router.push(`/order/${row.id}`)
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
      <div class="spacer" />
      <el-button :icon="Download" :loading="exporting" @click="exportCsv">导出 CSV</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="orderNo" label="订单号" min-width="190" />
        <el-table-column prop="userId" label="用户 ID" width="90" />
        <!-- 虚拟订单没有物流、支付即发放：列表里要能一眼区分，别让客服点错"发货" -->
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.orderType === 2 ? 'warning' : 'info'">
              {{ row.orderTypeText || '实物' }}
            </el-tag>
          </template>
        </el-table-column>
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

<style scoped>
.spacer {
  flex: 1;
}
</style>

