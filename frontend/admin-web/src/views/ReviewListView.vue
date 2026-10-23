<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reviewApi } from '@/api'
import type { ReviewVO } from '@/types'

const list = ref<ReviewVO[]>([])
const total = ref(0)
const loading = ref(false)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  status: undefined as number | undefined,
  productId: undefined as number | undefined
})

function fmt(v?: string) {
  return v ? v.replace('T', ' ').slice(0, 16) : '-'
}

/** 后台只是抽检图片，不做相册组件，新窗口打开原图足够 */
function openImage(url: string) {
  window.open(url, '_blank')
}

async function load() {
  loading.value = true
  try {
    const p = await reviewApi.page({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status,
      productId: query.productId
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
  query.productId = undefined
  search()
}

async function toggle(row: ReviewVO) {
  const hide = row.status === 1
  try {
    await ElMessageBox.confirm(
      hide ? '隐藏后该评价不再展示，商品评分会重新计算。' : '恢复后该评价会重新展示。',
      hide ? '隐藏评价' : '恢复评价',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await reviewApi.updateStatus(row.id, hide ? 0 : 1)
    ElMessage.success(hide ? '已隐藏' : '已恢复')
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
      <el-input-number
        v-model="query.productId"
        :min="1"
        :controls="false"
        placeholder="商品 ID"
        style="width: 140px"
      />
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="search">
        <el-option label="展示中" :value="1" />
        <el-option label="已隐藏" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="spacer" />
      <el-button @click="load">刷新</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" empty-text="还没有评价">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="productName" label="商品" min-width="180" />
        <el-table-column prop="spec" label="规格" width="110" />
        <el-table-column label="评分" width="150">
          <template #default="{ row }">
            <el-rate :model-value="row.rating" disabled size="small" />
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="220">
          <template #default="{ row }">
            <span :class="{ muted: !row.content }">{{ row.content || '（未填写）' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="图片" width="170">
          <template #default="{ row }">
            <div v-if="row.images && row.images.length" class="thumbs">
              <img
                v-for="(img, i) in row.images"
                :key="i"
                :src="img"
                class="thumb"
                alt="review"
                @click="openImage(img)"
              />
            </div>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="userLabel" label="用户" width="120" />
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmt(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '展示中' : '已隐藏' }}
            </el-tag>
            <el-tag v-if="row.refunded === 1" type="warning" class="ml">已退款</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggle(row)">
              {{ row.status === 1 ? '隐藏' : '恢复' }}
            </el-button>
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

<style scoped>
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.muted {
  color: #c0c4cc;
}

.ml {
  margin-left: 4px;
}

.thumbs {
  display: flex;
  gap: 4px;
}

.thumb {
  width: 44px;
  height: 44px;
  object-fit: cover;
  border-radius: 4px;
  cursor: pointer;
}
</style>
