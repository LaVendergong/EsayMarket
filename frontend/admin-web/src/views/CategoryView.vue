<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { categoryApi } from '@/api'
import type { CategoryDTO, CategoryVO } from '@/types'

const tree = ref<CategoryVO[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive<CategoryDTO & { id?: number }>({
  id: undefined,
  parentId: 0,
  name: '',
  sort: 0,
  icon: '',
  status: 1
})

const dialogTitle = computed(() => (form.id ? '编辑分类' : '新增分类'))

/** 树选择用：顶层塞一个「顶级分类」，编辑时把「自己」整棵子树摘掉（后端也会再校验） */
const parentOptions = computed<CategoryVO[]>(() => [
  { id: 0, parentId: -1, name: '顶级分类', sort: 0, status: 1, children: prune(tree.value, form.id) }
])

function prune(nodes: CategoryVO[], excludeId?: number): CategoryVO[] {
  return nodes
    .filter((n) => n.id !== excludeId)
    .map((n) => ({ ...n, children: prune(n.children || [], excludeId) }))
}

async function load() {
  loading.value = true
  try {
    tree.value = await categoryApi.tree()
  } finally {
    loading.value = false
  }
}

function openCreate(parentId: number) {
  Object.assign(form, { id: undefined, parentId, name: '', sort: 0, icon: '', status: 1 })
  dialogVisible.value = true
}

function openEdit(row: CategoryVO) {
  Object.assign(form, {
    id: row.id,
    parentId: row.parentId,
    name: row.name,
    sort: row.sort,
    icon: row.icon,
    status: row.status
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('分类名称不能为空')
    return
  }
  saving.value = true
  try {
    const dto: CategoryDTO = {
      parentId: form.parentId || 0,
      name: form.name.trim(),
      sort: form.sort ?? 0,
      icon: form.icon,
      status: form.status
    }
    if (form.id) {
      await categoryApi.update(form.id, dto)
    } else {
      await categoryApi.create(dto)
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

async function toggleStatus(row: CategoryVO) {
  try {
    await categoryApi.updateStatus(row.id, row.status === 1 ? 0 : 1)
    ElMessage.success(row.status === 1 ? '已禁用' : '已启用')
    await load()
  } catch {
    // 拦截器已提示
  }
}

async function remove(row: CategoryVO) {
  try {
    await ElMessageBox.confirm(`确定删除分类「${row.name}」？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await categoryApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 有子分类或商品时后端返回 2006，拦截器已提示
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openCreate(0)">新增顶级分类</el-button>
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="tree"
        row-key="id"
        default-expand-all
        :tree-props="{ children: 'children' }"
      >
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="分类名称" min-width="220" />
        <el-table-column prop="sort" label="排序" width="90" />
        <el-table-column prop="icon" label="图标" min-width="140">
          <template #default="{ row }">
            <span v-if="row.icon">{{ row.icon }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '正常' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCreate(row.id)">加子分类</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 1 ? '禁用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="460px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="上级分类">
          <el-tree-select
            v-model="form.parentId"
            :data="parentOptions"
            :props="{ label: 'name', value: 'id', children: 'children' }"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            default-expand-all
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="64" placeholder="分类名称" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="图标">
          <el-input v-model="form.icon" maxlength="255" placeholder="图标 URL 或标识，可空" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">正常</el-radio>
            <el-radio :value="0">禁用</el-radio>
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
