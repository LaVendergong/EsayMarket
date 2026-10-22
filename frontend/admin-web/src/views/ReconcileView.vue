<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { reconcileApi } from '@/api'
import type { ReconcileBatchVO } from '@/types'

const channel = ref('MOCK')
const billDate = ref(new Date().toISOString().slice(0, 10))
const content = ref('')
const fileName = ref('')
const submitting = ref(false)

const result = ref<ReconcileBatchVO | null>(null)
const history = ref<ReconcileBatchVO[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 10
const loading = ref(false)

const CHANNELS = [
  { value: 'MOCK', label: '模拟支付（归一化账单）' },
  { value: 'ALIPAY_WAP', label: '支付宝' },
  { value: 'WECHAT_NATIVE', label: '微信支付' }
]

const DIFF_TAG: Record<string, 'danger' | 'warning' | 'info'> = {
  MISSING_LOCAL: 'danger',
  MISSING_CHANNEL: 'warning',
  AMOUNT_MISMATCH: 'info'
}

/** 选择账单文件后读成文本（账单一般几百 KB，直接放进请求体） */
function onFileChange(file: { raw?: File }) {
  const raw = file.raw
  if (!raw) return
  const reader = new FileReader()
  reader.onload = () => {
    content.value = String(reader.result || '')
    fileName.value = raw.name
  }
  reader.readAsText(raw, 'utf-8')
}

async function submit() {
  if (!content.value.trim()) {
    ElMessage.warning('请先选择或粘贴账单内容')
    return
  }
  submitting.value = true
  try {
    result.value = await reconcileApi.submit({
      channel: channel.value,
      billDate: billDate.value,
      content: content.value
    })
    ElMessage.success('对账完成')
    pageNum.value = 1
    loadHistory()
  } finally {
    submitting.value = false
  }
}

async function loadHistory() {
  loading.value = true
  try {
    const page = await reconcileApi.page(pageNum.value, pageSize)
    history.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

async function openBatch(row: ReconcileBatchVO) {
  result.value = await reconcileApi.detail(row.id)
}

onMounted(loadHistory)
</script>

<template>
  <div class="page">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="对账回答的是「有没有哪笔钱渠道收了、我们不知道」。上传某渠道某天的账单，系统会与本地支付记录逐笔比对。"
      class="tip"
    />

    <el-card shadow="never" class="block">
      <el-form label-width="90px">
        <el-form-item label="支付渠道">
          <el-select v-model="channel" style="width: 260px">
            <el-option v-for="c in CHANNELS" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="账单日期">
          <el-date-picker v-model="billDate" type="date" value-format="YYYY-MM-DD" style="width: 200px" />
        </el-form-item>
        <el-form-item label="账单文件">
          <el-upload :auto-upload="false" :show-file-list="false" accept=".csv,.txt" :on-change="onFileChange">
            <el-button>选择账单文件</el-button>
          </el-upload>
          <span v-if="fileName" class="file">{{ fileName }}</span>
        </el-form-item>
        <el-form-item label="或粘贴内容">
          <el-input v-model="content" type="textarea" :rows="6" placeholder="out_trade_no,channel_trade_no,amount,status,biz_type" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">开始对账</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="result" shadow="never" class="block">
      <template #header>
        <div class="head">
          <span>对账结果 #{{ result.id }}（{{ result.channelText }} {{ result.billDate }}）</span>
          <el-tag :type="result.status === 1 ? 'success' : 'danger'">{{ result.statusText }}</el-tag>
        </div>
      </template>

      <el-descriptions :column="5" border>
        <el-descriptions-item label="账单条数">{{ result.totalLines }}</el-descriptions-item>
        <el-descriptions-item label="一致">{{ result.matched }}</el-descriptions-item>
        <el-descriptions-item label="渠道有/本地无">
          <span :class="{ bad: result.missingLocal > 0 }">{{ result.missingLocal }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="本地有/渠道无">
          <span :class="{ bad: result.missingChannel > 0 }">{{ result.missingChannel }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="金额不一致">
          <span :class="{ bad: result.amountMismatch > 0 }">{{ result.amountMismatch }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-descriptions :column="5" border class="diffs">
        <el-descriptions-item label="退款条数">{{ result.refundTotalLines ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="退款一致">{{ result.refundMatched ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="渠道有/本地无">
          <span :class="{ bad: (result.refundMissingLocal ?? 0) > 0 }">{{ result.refundMissingLocal ?? 0 }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="本地有/渠道无">
          <span :class="{ bad: (result.refundMissingChannel ?? 0) > 0 }">{{ result.refundMissingChannel ?? 0 }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="金额不一致">
          <span :class="{ bad: (result.refundAmountMismatch ?? 0) > 0 }">{{ result.refundAmountMismatch ?? 0 }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-table v-if="result.diffs && result.diffs.length" :data="result.diffs" border class="diffs">
        <el-table-column label="业务" width="80">
          <template #default="{ row }">{{ row.bizType === 'REFUND' ? '退款' : '支付' }}</template>
        </el-table-column>
        <el-table-column label="差异类型" width="160">
          <template #default="{ row }">
            <el-tag :type="DIFF_TAG[row.diffType] || 'info'">{{ row.diffTypeText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="单号" width="240">
          <template #default="{ row }">{{ row.bizType === 'REFUND' ? row.refundNo : row.paymentNo }}</template>
        </el-table-column>
        <el-table-column prop="channelTradeNo" label="渠道交易号" width="200" />
        <el-table-column prop="localAmount" label="本地金额" width="110" />
        <el-table-column prop="channelAmount" label="账单金额" width="110" />
        <el-table-column prop="detail" label="说明" min-width="220" />
      </el-table>
      <el-empty v-else description="没有任何差异" :image-size="60" />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><span>历史批次</span></template>
      <el-table v-loading="loading" :data="history" border @row-click="openBatch">
        <el-table-column prop="id" label="批次" width="80" />
        <el-table-column prop="channelText" label="渠道" width="120" />
        <el-table-column prop="billDate" label="账单日" width="120" />
        <el-table-column prop="totalLines" label="条数" width="80" />
        <el-table-column prop="matched" label="一致" width="80" />
        <el-table-column prop="missingLocal" label="渠道有/本地无" width="130" />
        <el-table-column prop="missingChannel" label="本地有/渠道无" width="130" />
        <el-table-column prop="amountMismatch" label="金额不符" width="100" />
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="执行时间" min-width="170" />
      </el-table>
      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="pageNum"
        @current-change="(p: number) => { pageNum = p; loadHistory() }"
      />
    </el-card>
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.block {
  margin-bottom: 12px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.file {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}

.diffs {
  margin-top: 12px;
}

.bad {
  color: #f56c6c;
  font-weight: 600;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
