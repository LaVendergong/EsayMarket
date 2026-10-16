<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { categoryApi, productApi } from '@/api'
import type { CategoryVO, ProductDTO, SkuDTO } from '@/types'

const route = useRoute()
const router = useRouter()

const id = computed(() => {
  const raw = route.params.id
  return raw ? Number(Array.isArray(raw) ? raw[0] : raw) : undefined
})
const isEdit = computed(() => id.value !== undefined)

const categories = ref<CategoryVO[]>([])
const loading = ref(false)
const saving = ref(false)

// 表单用「字段都比 DTO 宽松但一定有值」的形状，省得在模板里到处判空
const form = reactive({
  categoryId: null as number | null,
  name: '',
  subTitle: '',
  mainImage: '',
  images: [] as string[],
  detail: '',
  price: null as number | null,
  status: 1,
  skus: [] as SkuDTO[]
})

function addSku() {
  form.skus.push({ id: undefined, skuCode: '', spec: '', image: '', price: null, stock: null })
}

async function loadDetail() {
  if (!isEdit.value) {
    if (form.skus.length === 0) addSku()
    return
  }
  loading.value = true
  try {
    const d = await productApi.detail(id.value!)
    form.categoryId = d.categoryId
    form.name = d.name
    form.subTitle = d.subTitle ?? ''
    form.mainImage = d.mainImage ?? ''
    form.images = [...(d.images || [])]
    form.detail = d.detail ?? ''
    form.price = d.price
    form.status = d.status
    // 带上 id 的走更新，没 id 的（新增行）后端按新增处理
    form.skus = d.skus.map((s) => ({
      id: s.id,
      skuCode: s.skuCode ?? '',
      spec: s.spec ?? '',
      image: s.image ?? '',
      price: s.price,
      stock: s.stock
    }))
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!form.categoryId) {
    ElMessage.warning('请选择分类')
    return
  }
  if (!form.name.trim()) {
    ElMessage.warning('请输入商品名称')
    return
  }
  if (form.price === null || form.price === undefined) {
    ElMessage.warning('请输入商品价格')
    return
  }
  if (form.skus.length === 0) {
    ElMessage.warning('至少需要一个 SKU')
    return
  }
  for (const [i, s] of form.skus.entries()) {
    if (s.price === null || s.price === undefined) {
      ElMessage.warning(`第 ${i + 1} 个 SKU 价格不能为空`)
      return
    }
    if (s.stock === null || s.stock === undefined) {
      ElMessage.warning(`第 ${i + 1} 个 SKU 库存不能为空`)
      return
    }
  }

  const dto: ProductDTO = {
    categoryId: form.categoryId,
    name: form.name.trim(),
    subTitle: form.subTitle || undefined,
    mainImage: form.mainImage || undefined,
    images: form.images.map((s) => s.trim()).filter(Boolean),
    detail: form.detail || undefined,
    price: form.price,
    status: form.status,
    skus: form.skus.map<SkuDTO>((s) => ({
      id: s.id,
      skuCode: s.skuCode || undefined,
      spec: s.spec || undefined,
      image: s.image || undefined,
      price: s.price,
      stock: s.stock
    }))
  }

  saving.value = true
  try {
    if (isEdit.value) {
      await productApi.update(id.value!, dto)
    } else {
      await productApi.create(dto)
    }
    ElMessage.success('保存成功')
    router.push('/product')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  categories.value = await categoryApi.tree()
  await loadDetail()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <el-card shadow="never">
      <template #header>
        <span>{{ isEdit ? `编辑商品 #${id}` : '新增商品' }}</span>
      </template>

      <el-form :model="form" label-width="100px">
        <el-form-item label="分类" required>
          <el-tree-select
            v-model="form.categoryId"
            :data="categories"
            :props="{ label: 'name', value: 'id', children: 'children' }"
            node-key="id"
            check-strictly
            default-expand-all
            :render-after-expand="false"
            placeholder="选择分类"
            style="width: 320px"
          />
        </el-form-item>
        <el-form-item label="商品名称" required>
          <el-input v-model="form.name" maxlength="128" placeholder="商品名称" style="width: 420px" />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="form.subTitle" maxlength="255" placeholder="副标题，可空" style="width: 420px" />
        </el-form-item>
        <el-form-item label="主图">
          <el-input v-model="form.mainImage" maxlength="255" placeholder="主图 URL，可空" style="width: 420px" />
        </el-form-item>
        <el-form-item label="图集">
          <div class="img-editor">
            <div v-for="(img, i) in form.images" :key="i" class="img-row">
              <el-input v-model="form.images[i]" placeholder="图片 URL" style="width: 380px" />
              <el-button link type="danger" @click="form.images.splice(i, 1)">删除</el-button>
            </div>
            <el-button link type="primary" :icon="Plus" @click="form.images.push('')">添加图片</el-button>
          </div>
        </el-form-item>
        <el-form-item label="展示价" required>
          <el-input-number v-model="form.price" :min="0" :precision="2" :step="1" controls-position="right" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">上架</el-radio>
            <el-radio :value="0">下架</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="详情">
          <el-input v-model="form.detail" type="textarea" :rows="4" placeholder="商品详情，可空" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="skus">
      <template #header>
        <div class="sku-head">
          <span>SKU（库存记在 SKU 上，商品列表的库存是它们的可售合计）</span>
          <el-button type="primary" size="small" :icon="Plus" @click="addSku">添加 SKU</el-button>
        </div>
      </template>
      <el-table :data="form.skus" empty-text="至少添加一个 SKU">
        <el-table-column label="ID" width="90">
          <template #default="{ row }">
            <span v-if="row.id">{{ row.id }}</span>
            <el-tag v-else size="small" type="info">新增</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="SKU 编码" width="160">
          <template #default="{ row }">
            <el-input v-model="row.skuCode" placeholder="可空" />
          </template>
        </el-table-column>
        <el-table-column label="规格" width="170">
          <template #default="{ row }">
            <el-input v-model="row.spec" placeholder="不填按「默认」" />
          </template>
        </el-table-column>
        <el-table-column label="图片" min-width="200">
          <template #default="{ row }">
            <el-input v-model="row.image" placeholder="可空" />
          </template>
        </el-table-column>
        <el-table-column label="价格" width="160">
          <template #default="{ row }">
            <el-input-number
              v-model="row.price"
              :min="0"
              :precision="2"
              :step="1"
              controls-position="right"
              style="width: 100%"
            />
          </template>
        </el-table-column>
        <el-table-column label="总库存" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.stock" :min="0" :step="1" controls-position="right" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ $index }">
            <el-button link type="danger" @click="form.skus.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <div class="actions">
      <el-button @click="router.push('/product')">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </div>
  </div>
</template>

<style scoped>
.skus {
  margin-top: 16px;
}

.sku-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.img-editor {
  width: 100%;
}

.img-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}
</style>
