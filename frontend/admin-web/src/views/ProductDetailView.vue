<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { productApi } from '@/api'
import type { ProductDetailVO } from '@/types'
import { money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const id = computed(() => {
  const raw = route.params.id
  return Number(Array.isArray(raw) ? raw[0] : raw)
})

const p = ref<ProductDetailVO | null>(null)
const loading = ref(false)

/** 详情页只读，不做富文本渲染：把标签剥掉当纯文本显示，避免给后台开一个 XSS 口子 */
const detailText = computed(() => (p.value?.detail || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim())

const totalStock = computed(() => (p.value?.skus || []).reduce((s, k) => s + (k.availableStock || 0), 0))

onMounted(async () => {
  loading.value = true
  try {
    p.value = await productApi.detail(id.value)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="toolbar">
      <el-button :icon="ArrowLeft" @click="router.push('/product')">返回商品列表</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="router.push(`/product/${id}/edit`)">编辑</el-button>
    </div>

    <template v-if="p">
      <el-card shadow="never">
        <template #header>
          <div class="head">
            <span>商品 #{{ p.id }}</span>
            <el-tag :type="p.status === 1 ? 'success' : 'info'">{{ p.status === 1 ? '上架' : '下架' }}</el-tag>
          </div>
        </template>

        <div class="top">
          <el-image v-if="p.mainImage" :src="p.mainImage" class="cover" fit="cover" :preview-src-list="p.images" />
          <div v-else class="cover placeholder muted">无主图</div>

          <el-descriptions :column="2" border class="info">
            <el-descriptions-item label="商品名称">{{ p.name }}</el-descriptions-item>
            <el-descriptions-item label="所属分类">{{ p.categoryName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="副标题">{{ p.subTitle || '-' }}</el-descriptions-item>
            <el-descriptions-item label="展示价">{{ money(p.price) }}</el-descriptions-item>
            <el-descriptions-item label="销量">{{ p.sales }}</el-descriptions-item>
            <el-descriptions-item label="可售库存合计">{{ totalStock }}</el-descriptions-item>
          </el-descriptions>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>图集（点击可预览）</template>
        <div class="gallery">
          <el-image
            v-for="(img, i) in p.images"
            :key="i"
            :src="img"
            class="thumb-lg"
            fit="cover"
            :preview-src-list="p.images"
            :initial-index="i"
          />
          <span v-if="!p.images.length" class="muted">无</span>
        </div>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>SKU（<span class="muted">可售 = 总库存 - 待支付订单已锁定</span>）</template>
        <el-table :data="p.skus" empty-text="没有 SKU">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="skuCode" label="SKU 编码" width="160">
            <template #default="{ row }">
              <span v-if="row.skuCode">{{ row.skuCode }}</span>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="spec" label="规格" min-width="160" />
          <el-table-column label="图片" width="80">
            <template #default="{ row }">
              <el-image v-if="row.image" :src="row.image" class="thumb" fit="cover" />
              <div v-else class="thumb" />
            </template>
          </el-table-column>
          <el-table-column label="价格" width="120">
            <template #default="{ row }">{{ money(row.price) }}</template>
          </el-table-column>
          <el-table-column prop="stock" label="总库存" width="100" />
          <el-table-column prop="lockedStock" label="已锁定" width="100" />
          <el-table-column label="可售" width="100">
            <template #default="{ row }">
              <span :class="{ 'zero': !row.availableStock }">{{ row.availableStock }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>商品详情</template>
        <div class="detail">{{ detailText || '无' }}</div>
      </el-card>
    </template>

    <el-empty v-else-if="!loading" description="商品不存在" />
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.top {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.cover {
  width: 220px;
  height: 220px;
  border-radius: 6px;
  flex: none;
  background: #f0f2f5;
}

.placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
}

.info {
  flex: 1;
  min-width: 0;
}

.block {
  margin-top: 16px;
}

.gallery {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.thumb-lg {
  width: 120px;
  height: 120px;
  border-radius: 6px;
  background: #f0f2f5;
}

.zero {
  color: #f56c6c;
  font-weight: 600;
}

.detail {
  line-height: 1.7;
  color: #606266;
}
</style>
