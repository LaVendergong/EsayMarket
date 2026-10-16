<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { orderApi } from '@/api'
import { ORDER_STATUS, type OrderVO } from '@/types'
import { formatDateTime, money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const order = ref<OrderVO | null>(null)
const loading = ref(false)
/** 加载失败（最常见的是 3001 订单不存在），用于展示可读的空状态而不是让页面空着 */
const loadFailed = ref(false)

function statusTag(status: number) {
  return ORDER_STATUS.find((s) => s.value === status)?.tag ?? 'info'
}

onMounted(async () => {
  const raw = route.params.id
  const id = Number(Array.isArray(raw) ? raw[0] : raw)
  loading.value = true
  try {
    order.value = await orderApi.detail(id)
    loadFailed.value = false
  } catch {
    // 同 portal-web 的订单详情：业务失败要在页面里兜住，别让它冒到错误边界把整页换成兜底 UI
    loadFailed.value = true
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="toolbar">
      <el-button :icon="ArrowLeft" @click="router.push('/order')">返回订单列表</el-button>
    </div>

    <el-empty v-if="!order && !loading" :description="loadFailed ? '订单不存在或已被删除' : '暂无数据'" />

    <template v-if="order">
      <el-card shadow="never">
        <template #header>
          <div class="head">
            <span>订单 {{ order.orderNo }}</span>
            <el-tag :type="statusTag(order.status)">{{ order.statusText }}</el-tag>
          </div>
        </template>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="订单 ID">{{ order.id }}</el-descriptions-item>
          <el-descriptions-item label="用户 ID">{{ order.userId }}</el-descriptions-item>
          <el-descriptions-item label="实付金额">{{ money(order.payAmount) }}</el-descriptions-item>
          <el-descriptions-item label="订单金额">{{ money(order.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="收货人">{{ order.receiver || '-' }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ order.receiverPhone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="收货地址" :span="3">{{ order.receiverAddr || '-' }}</el-descriptions-item>
          <el-descriptions-item label="下单时间">{{ formatDateTime(order.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="支付时间">{{ formatDateTime(order.payTime) }}</el-descriptions-item>
          <el-descriptions-item label="超时关单时间">{{ formatDateTime(order.expireTime) }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" class="items">
        <template #header>商品明细</template>
        <el-table :data="order.items">
          <el-table-column label="图片" width="80">
            <template #default="{ row }">
              <el-image v-if="row.productImage" :src="row.productImage" class="thumb" fit="cover" />
              <div v-else class="thumb" />
            </template>
          </el-table-column>
          <el-table-column prop="productName" label="商品" min-width="220" />
          <el-table-column prop="spec" label="规格" width="140" />
          <el-table-column label="单价" width="120">
            <template #default="{ row }">{{ money(row.price) }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="90" />
          <el-table-column label="小计" width="130">
            <template #default="{ row }">{{ money(row.totalAmount) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.items {
  margin-top: 16px;
}
</style>
