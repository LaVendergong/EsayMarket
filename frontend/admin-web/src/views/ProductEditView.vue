<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { cardKeyApi, categoryApi, freightApi, productApi } from '@/api'
import type { CategoryVO, FreightTemplateVO, ProductDTO, SkuDTO } from '@/types'

const route = useRoute()
const router = useRouter()

const id = computed(() => {
  const raw = route.params.id
  return raw ? Number(Array.isArray(raw) ? raw[0] : raw) : undefined
})
const isEdit = computed(() => id.value !== undefined)

const categories = ref<CategoryVO[]>([])
const freights = ref<FreightTemplateVO[]>([])
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
  freightTemplateId: null as number | null,
  productType: 1,
  deliveryType: null as number | null,
  deliveryUrl: '',
  skus: [] as SkuDTO[]
})

/** 卡密导入：卡密类商品才有，且要先保存商品（需要 skuId） */
const keyDialog = ref(false)
const keySkuId = ref<number | null>(null)
const keyText = ref('')
const keySaving = ref(false)

/** 选 CSV/文本文件：前端读完直接填进输入框，后端只保留一条导入路径（不做 multipart） */
function onKeyFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    keyText.value = String(reader.result || '')
    ElMessage.success(`已读取 ${file.name}`)
  }
  reader.readAsText(file, 'utf-8')
  // 清空 value，否则连续选同一个文件不会触发 change
  input.value = ''
}

function openKeyImport(skuId?: number) {
  if (!skuId) {
    ElMessage.warning('请先保存商品，再导入卡密')
    return
  }
  keySkuId.value = skuId
  keyText.value = ''
  keyDialog.value = true
}

async function saveKeys() {
  const lines = keyText.value
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
  if (!lines.length) {
    ElMessage.warning('请粘贴卡密，每行一张（卡号,卡密）')
    return
  }
  keySaving.value = true
  try {
    const n = await cardKeyApi.importKeys(keySkuId.value!, lines)
    ElMessage.success(`已导入 ${n} 张，库存同步增加 ${n}`)
    keyDialog.value = false
    await loadDetail()
  } catch {
    // 拦截器已提示
  } finally {
    keySaving.value = false
  }
}

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
    form.freightTemplateId = d.freightTemplateId ?? null
    form.productType = d.productType ?? 1
    form.deliveryType = d.deliveryType ?? null
    form.deliveryUrl = d.deliveryUrl ?? ''
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
  if (form.productType === 2) {
    if (!form.deliveryType) {
      ElMessage.warning('虚拟商品必须选择发放方式（卡密 / 外链）')
      return
    }
    if (form.deliveryType === 2 && !form.deliveryUrl.trim()) {
      ElMessage.warning('外链类商品必须填写下载地址')
      return
    }
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
    productType: form.productType,
    deliveryType: form.productType === 2 ? form.deliveryType : undefined,
    deliveryUrl: form.productType === 2 && form.deliveryType === 2 ? form.deliveryUrl.trim() : undefined,
    // 虚拟商品不收运费，别把下拉里残留的模板传上去
    freightTemplateId: form.productType === 1 ? form.freightTemplateId ?? undefined : undefined,
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
  // 停用的模板也要列出来：商品可能还绑着它（计价时按包邮处理），编辑时不该凭空消失
  freights.value = await freightApi.list()
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
        <el-form-item label="商品类型" required>
          <el-radio-group v-model="form.productType">
            <el-radio :value="1">实物（要地址、要运费）</el-radio>
            <el-radio :value="2">虚拟（支付后自动发放）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.productType === 2" label="发放方式" required>
          <el-radio-group v-model="form.deliveryType">
            <el-radio :value="1">卡密（从卡密池发放）</el-radio>
            <el-radio :value="2">外链（下载地址）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.productType === 2 && form.deliveryType === 2" label="下载地址" required>
          <el-input v-model="form.deliveryUrl" maxlength="512" placeholder="https://...（付款后才会展示给买家）" style="width: 420px" />
        </el-form-item>
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
        <el-form-item v-if="form.productType === 1" label="运费模板">
          <el-select
            v-model="form.freightTemplateId"
            clearable
            placeholder="不选 = 包邮"
            style="width: 320px"
          >
            <el-option
              v-for="f in freights"
              :key="f.id"
              :label="f.status === 1 ? f.name : `${f.name}（已停用，按包邮）`"
              :value="f.id"
            />
          </el-select>
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
        <el-table-column label="操作" :width="form.productType === 2 && form.deliveryType === 1 ? 170 : 90">
          <template #default="{ row, $index }">
            <el-button
              v-if="form.productType === 2 && form.deliveryType === 1"
              link
              type="primary"
              @click="openKeyImport(row.id)"
            >
              导入卡密
            </el-button>
            <el-button link type="danger" @click="form.skus.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <div class="actions">
      <el-button @click="router.push('/product')">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </div>

    <el-dialog v-model="keyDialog" title="导入卡密" width="520px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="每行一张，格式「卡号,卡密」（逗号或 Tab 分隔）。导入多少张，SKU 库存就自动加多少。"
        class="key-tip"
      />
      <!-- 不搞 multipart 上传：文件在前端读成文本塞进同一个输入框，后端只有一条导入路径 -->
      <div class="key-file">
        <input ref="keyFileInput" type="file" accept=".csv,.txt" @change="onKeyFile" />
      </div>
      <el-input
        v-model="keyText"
        type="textarea"
        :rows="10"
        placeholder="CARD001,SECRET001&#10;CARD002,SECRET002"
      />
      <template #footer>
        <el-button @click="keyDialog = false">取消</el-button>
        <el-button type="primary" :loading="keySaving" @click="saveKeys">导入</el-button>
      </template>
    </el-dialog>
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

.key-tip {
  margin-bottom: 12px;
}
</style>
