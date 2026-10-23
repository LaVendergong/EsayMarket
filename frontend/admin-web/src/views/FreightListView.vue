<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { freightApi } from '@/api'
import type { FreightTemplateDTO, FreightTemplateVO } from '@/types'

const list = ref<FreightTemplateVO[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({
  id: undefined as number | undefined,
  name: '',
  type: 'FIXED',
  baseFee: 8 as number | null,
  freeThreshold: 99 as number | null,
  status: 1
})

const dialogTitle = computed(() => (form.id ? '编辑运费模板' : '新增运费模板'))

async function load() {
  loading.value = true
  try {
    list.value = await freightApi.list()
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, {
    id: undefined,
    name: '',
    type: 'FIXED',
    baseFee: 8,
    freeThreshold: 99,
    status: 1
  })
  dialogVisible.value = true
}

function openEdit(row: FreightTemplateVO) {
  Object.assign(form, {
    id: row.id,
    name: row.name,
    type: row.type,
    baseFee: row.baseFee,
    freeThreshold: row.freeThreshold ?? null,
    status: row.status
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请输入模板名')
    return
  }
  if (form.type === 'FREE_OVER' && (form.freeThreshold === null || form.freeThreshold <= 0)) {
    ElMessage.warning('满额包邮必须填写包邮门槛')
    return
  }

  const dto: FreightTemplateDTO = {
    name: form.name.trim(),
    type: form.type,
    baseFee: form.baseFee,
    // 固定运费模板不传门槛，避免后台看到「满 99 包邮」的错觉
    freeThreshold: form.type === 'FREE_OVER' ? form.freeThreshold : undefined,
    status: form.status
  }

  saving.value = true
  try {
    if (form.id) {
      await freightApi.update(form.id, dto)
    } else {
      await freightApi.create(dto)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await load()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: FreightTemplateVO) {
  try {
    await freightApi.updateStatus(row.id, row.status === 1 ? 0 : 1)
    ElMessage.success(row.status === 1 ? '已停用（计价时按包邮处理）' : '已启用')
    await load()
  } catch {
    // 拦截器已提示
  }
}

async function remove(row: FreightTemplateVO) {
  try {
    await ElMessageBox.confirm(`确定删除运费模板「${row.name}」？还有商品在用时不能删。`, '提示', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await freightApi.remove(row.id)
    ElMessage.success('已删除')
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
      <span class="tip">
        商品绑定模板后才收运费；不绑定 = 包邮。满额包邮按「同一模板下的商品金额合计」判断。
      </span>
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增模板</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" empty-text="还没有运费模板">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="模板名" min-width="180" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag :type="row.type === 'FREE_OVER' ? 'success' : 'info'">{{ row.typeText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="基础运费" width="120">
          <template #default="{ row }">¥{{ row.baseFee }}</template>
        </el-table-column>
        <el-table-column label="包邮门槛" width="140">
          <template #default="{ row }">
            <span v-if="row.freeThreshold">满 ¥{{ row.freeThreshold }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="模板名" required>
          <el-input v-model="form.name" maxlength="64" placeholder="如「标准快递 8 元」" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.type">
            <el-radio value="FIXED">固定运费</el-radio>
            <el-radio value="FREE_OVER">满额包邮</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="基础运费">
          <el-input-number v-model="form.baseFee" :min="0" :precision="2" :step="1" controls-position="right" />
          <span class="hint">未达包邮门槛时收这个金额</span>
        </el-form-item>
        <el-form-item v-if="form.type === 'FREE_OVER'" label="包邮门槛">
          <el-input-number
            v-model="form.freeThreshold"
            :min="0"
            :precision="2"
            :step="10"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tip {
  color: #909399;
  font-size: 12px;
}

.hint {
  margin-left: 12px;
  color: #909399;
  font-size: 12px;
}

.muted {
  color: #c0c4cc;
}
</style>
