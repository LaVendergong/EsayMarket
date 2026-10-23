<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { couponApi } from '@/api'
import type { CouponDTO, CouponRecordVO, CouponVO } from '@/types'

const list = ref<CouponVO[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  status: undefined as number | undefined,
  keyword: ''
})

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({
  id: undefined as number | undefined,
  name: '',
  threshold: 0 as number | null,
  amount: 10 as number | null,
  total: 100 as number | null,
  validFrom: null as string | null,
  validTo: null as string | null,
  status: 1
})

const dialogTitle = computed(() => (form.id ? '编辑优惠券' : '新增优惠券'))

/** 后端返回 2026-10-23T10:00:00，表格里只需要「到分钟」，别把 T 露给运营看 */
function fmt(v?: string | null) {
  return v ? v.replace('T', ' ').slice(0, 16) : '长期'
}

/** 明细里的时间：没有就显示 -（不能复用 fmt，"长期"在领取/使用时间上没有意义） */
function fmtTime(v?: string | null) {
  return v ? v.replace('T', ' ').slice(0, 16) : '-'
}

// ---------------------------------------------------------------- 领取明细
const recordsVisible = ref(false)
const recordsLoading = ref(false)
const recordsTitle = ref('')
const records = ref<CouponRecordVO[]>([])

async function openRecords(row: CouponVO) {
  recordsTitle.value = row.name
  records.value = []
  recordsVisible.value = true
  recordsLoading.value = true
  try {
    records.value = await couponApi.records(row.id)
  } catch {
    // 拦截器已提示
  } finally {
    recordsLoading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const p = await couponApi.page({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status,
      keyword: query.keyword || undefined
    })
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

function reset() {
  query.status = undefined
  query.keyword = ''
  search()
}

function openCreate() {
  Object.assign(form, {
    id: undefined,
    name: '',
    threshold: 0,
    amount: 10,
    total: 100,
    validFrom: null,
    validTo: null,
    status: 1
  })
  dialogVisible.value = true
}

function openEdit(row: CouponVO) {
  Object.assign(form, {
    id: row.id,
    name: row.name,
    threshold: row.threshold,
    amount: row.amount,
    total: row.total,
    validFrom: row.validFrom ?? null,
    validTo: row.validTo ?? null,
    status: row.status
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入券名')
    return
  }
  if (form.threshold === null || form.amount === null || form.total === null) {
    ElMessage.warning('门槛、优惠金额、发放总量都必填')
    return
  }
  if (form.amount <= 0) {
    ElMessage.warning('优惠金额必须大于 0')
    return
  }
  if (form.total < 0) {
    ElMessage.warning('发放总量不能小于 0')
    return
  }
  if (form.validFrom && form.validTo && form.validFrom >= form.validTo) {
    ElMessage.warning('生效时间必须早于失效时间')
    return
  }

  const dto: CouponDTO = {
    name: form.name.trim(),
    threshold: form.threshold,
    amount: form.amount,
    total: form.total,
    validFrom: form.validFrom || undefined,
    validTo: form.validTo || undefined,
    status: form.status
  }

  saving.value = true
  try {
    if (form.id) {
      await couponApi.update(form.id, dto)
    } else {
      await couponApi.create(dto)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await load()
  } catch {
    // 拦截器已提示（例如总量改到比已领还少）
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: CouponVO) {
  try {
    await couponApi.updateStatus(row.id, row.status === 1 ? 0 : 1)
    ElMessage.success(row.status === 1 ? '已停发' : '已恢复发放')
    await load()
  } catch {
    // 拦截器已提示
  }
}

async function remove(row: CouponVO) {
  try {
    await ElMessageBox.confirm(`确定删除优惠券「${row.name}」？已被领取的券不能删。`, '提示', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await couponApi.remove(row.id)
    ElMessage.success('已删除')
    // 删掉当前页最后一条时往前翻一页，否则会停在空页
    if (list.value.length === 1 && query.pageNum > 1) query.pageNum -= 1
    await load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="券名"
        clearable
        style="width: 200px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="search">
        <el-option label="可领" :value="1" />
        <el-option label="停发" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="spacer" />
      <el-button type="primary" :icon="Plus" @click="openCreate">新增优惠券</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" empty-text="还没有优惠券">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="券名" min-width="160" />
        <el-table-column label="使用门槛" width="130">
          <template #default="{ row }">满 {{ row.threshold }} 元</template>
        </el-table-column>
        <el-table-column label="优惠" width="110">
          <template #default="{ row }">
            <el-tag type="danger" effect="plain">-{{ row.amount }} 元</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="领取进度" width="120">
          <template #default="{ row }">{{ row.received }} / {{ row.total }}</template>
        </el-table-column>
        <el-table-column label="有效期" min-width="230">
          <template #default="{ row }">{{ fmt(row.validFrom) }} ~ {{ fmt(row.validTo) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '可领' : '停发' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRecords(row)">领取明细</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 1 ? '停发' : '恢复' }}
            </el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="券名" required>
          <el-input v-model="form.name" maxlength="64" placeholder="如「满 100 减 20」" />
        </el-form-item>
        <el-form-item label="使用门槛" required>
          <el-input-number v-model="form.threshold" :min="0" :precision="2" :step="10" controls-position="right" />
          <span class="hint">按商品金额判断，不含运费</span>
        </el-form-item>
        <el-form-item label="优惠金额" required>
          <el-input-number v-model="form.amount" :min="0.01" :precision="2" :step="5" controls-position="right" />
          <span class="hint">券只抵扣商品金额，不会抵运费</span>
        </el-form-item>
        <el-form-item label="发放总量" required>
          <el-input-number v-model="form.total" :min="0" :step="100" controls-position="right" />
          <span class="hint">每人限领一张</span>
        </el-form-item>
        <el-form-item label="生效时间">
          <el-date-picker
            v-model="form.validFrom"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="不填=立即生效"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="失效时间">
          <el-date-picker
            v-model="form.validTo"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss"
            placeholder="不填=长期有效"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">可领</el-radio>
            <el-radio :value="0">停发</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="recordsVisible" :title="`领取明细 · ${recordsTitle}`" width="760px">
      <el-table v-loading="recordsLoading" :data="records" empty-text="还没有人领取">
        <el-table-column prop="id" label="券 ID" width="80" />
        <el-table-column label="用户" min-width="150">
          <template #default="{ row }">{{ row.nickname || row.username || `#${row.userId}` }}</template>
        </el-table-column>
        <el-table-column prop="username" label="账号" width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : row.status === 1 ? 'primary' : 'info'">
              {{ row.statusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="领取时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.receiveTime) }}</template>
        </el-table-column>
        <el-table-column label="使用时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.useTime) }}</template>
        </el-table-column>
        <el-table-column label="订单" width="90">
          <template #default="{ row }">{{ row.orderId || '-' }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="recordsVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}
</style>
