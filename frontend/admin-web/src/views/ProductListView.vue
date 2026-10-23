<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { categoryApi, productApi } from '@/api'
import type { CategoryVO, ProductVO } from '@/types'

const router = useRouter()

const list = ref<ProductVO[]>([])
const total = ref(0)
const loading = ref(false)
const categories = ref<CategoryVO[]>([])

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  categoryId: undefined as number | undefined,
  keyword: '',
  status: undefined as number | undefined
})

async function load() {
  loading.value = true
  try {
    const page = await productApi.page({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      categoryId: query.categoryId,
      keyword: query.keyword || undefined,
      status: query.status
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
  query.categoryId = undefined
  query.keyword = ''
  query.status = undefined
  search()
}

async function toggleStatus(row: ProductVO) {
  const next = row.status === 1 ? 0 : 1
  try {
    await productApi.updateStatus(row.id, next)
    ElMessage.success(next === 1 ? '已上架' : '已下架')
    await load()
  } catch {
    // 拦截器已提示
  }
}

async function remove(row: ProductVO) {
  try {
    await ElMessageBox.confirm(`确定删除商品「${row.name}」？其 SKU 会一起删除。`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await productApi.remove(row.id)
    ElMessage.success('已删除')
    // 删掉当前页最后一条时回退一页，避免停在空页
    if (list.value.length === 1 && query.pageNum > 1) {
      query.pageNum -= 1
    }
    await load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  categories.value = await categoryApi.tree()
  await load()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-tree-select
        v-model="query.categoryId"
        :data="categories"
        :props="{ label: 'name', value: 'id', children: 'children' }"
        node-key="id"
        check-strictly
        clearable
        default-expand-all
        :render-after-expand="false"
        placeholder="全部分类（含子分类）"
        style="width: 220px"
      />
      <el-input
        v-model="query.keyword"
        placeholder="搜商品名 / 副标题"
        clearable
        style="width: 220px"
        @keyup.enter="search"
      />
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px">
        <el-option label="上架" :value="1" />
        <el-option label="下架" :value="0" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="search">查询</el-button>
      <el-button :icon="Refresh" @click="reset">重置</el-button>
      <div class="spacer" />
      <el-button type="primary" :icon="Plus" @click="router.push('/product/new')">新增商品</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="主图" width="80">
          <template #default="{ row }">
            <el-image v-if="row.mainImage" :src="row.mainImage" class="thumb" fit="cover" />
            <div v-else class="thumb" />
          </template>
        </el-table-column>
        <el-table-column label="商品" min-width="240">
          <template #default="{ row }">
            <div>{{ row.name }}</div>
            <div class="muted">{{ row.subTitle || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.productType === 2 ? 'warning' : 'info'">
              {{ row.productTypeText || '实物' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="120" />
        <el-table-column label="价格" width="110">
          <template #default="{ row }">¥{{ Number(row.price).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="stock" label="可售库存" width="100" />
        <el-table-column prop="sales" label="销量" width="80" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '上架' : '下架' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/product/${row.id}`)">详情</el-button>
            <el-button link type="primary" @click="router.push(`/product/${row.id}/edit`)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
              {{ row.status === 1 ? '下架' : '上架' }}
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
  </div>
</template>
