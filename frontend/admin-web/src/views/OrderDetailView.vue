<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
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

// ---------------------------------------------------------------- 发货
const shipVisible = ref(false)
const shipSaving = ref(false)
const shipForm = reactive({ company: 'SF', shipNo: '' })
const expressOptions = [
  { value: 'SF', label: '顺丰速运' },
  { value: 'ZTO', label: '中通快递' },
  { value: 'YTO', label: '圆通速递' },
  { value: 'YD', label: '韵达速递' },
  { value: 'JD', label: '京东物流' },
  { value: 'EMS', label: '邮政EMS' },
  { value: 'OTHER', label: '其他' }
]

async function submitShip() {
  if (!order.value) return
  if (!shipForm.shipNo.trim()) {
    ElMessage.warning('请填写运单号')
    return
  }
  shipSaving.value = true
  try {
    await orderApi.ship(order.value.id, { company: shipForm.company, shipNo: shipForm.shipNo.trim() })
    ElMessage.success('已发货')
    shipVisible.value = false
    order.value = await orderApi.detail(order.value.id)
  } catch {
    // 拦截器已提示
  } finally {
    shipSaving.value = false
  }
}

async function regrant() {
  if (!order.value) return
  try {
    await ElMessageBox.confirm('重新发放该虚拟商品的凭证？', '补发', { type: 'warning' })
  } catch {
    return
  }
  try {
    await orderApi.regrant(order.value.id)
    ElMessage.success('已补发')
    order.value = await orderApi.detail(order.value.id)
  } catch {
    // 拦截器已提示（卡密不足 -> 7105）
  }
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
      <div class="spacer" />
      <!-- 实物订单：发货要填快递公司 + 单号，否则用户和客服都查不到物流 -->
      <el-button v-if="order && order.orderType !== 2 && order.status === 1" type="primary" @click="shipVisible = true">
        发货
      </el-button>
      <!-- 虚拟订单支付成功却没发出东西时（卡密不足），停在这一步等人工补发 -->
      <el-button v-if="order && order.orderType === 2 && order.status === 1" type="primary" @click="regrant">
        补发凭证
      </el-button>
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
          <el-descriptions-item label="商品金额">{{ money(order.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="运费">{{ money(order.freightAmount ?? 0) }}</el-descriptions-item>
          <el-descriptions-item label="优惠">{{ order.discountAmount ? `-${money(order.discountAmount)}` : money(0) }}</el-descriptions-item>
          <el-descriptions-item v-if="order.shipNo" label="物流">
            {{ order.shipCompanyText }} / {{ order.shipNo }}
          </el-descriptions-item>
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
          <el-table-column label="小计" width="120">
            <template #default="{ row }">{{ money(row.totalAmount) }}</template>
          </el-table-column>
          <el-table-column label="分摊优惠" width="120">
            <template #default="{ row }">
              {{ row.discountAmount ? `-${money(row.discountAmount)}` : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="实付" width="120">
            <template #default="{ row }">{{ money(row.payAmount ?? row.totalAmount) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </template>

    <el-dialog v-model="shipVisible" title="发货" width="420px">
      <el-form :model="shipForm" label-width="90px">
        <el-form-item label="快递公司" required>
          <el-select v-model="shipForm.company" style="width: 100%">
            <el-option v-for="e in expressOptions" :key="e.value" :label="e.label" :value="e.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="运单号" required>
          <el-input v-model="shipForm.shipNo" maxlength="64" placeholder="运单号（字母/数字/短横线，4-64 位）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shipVisible = false">取消</el-button>
        <el-button type="primary" :loading="shipSaving" @click="submitShip">确认发货</el-button>
      </template>
    </el-dialog>
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

.spacer {
  flex: 1;
}
</style>
