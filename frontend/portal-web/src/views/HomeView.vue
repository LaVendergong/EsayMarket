<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { categoryApi, productApi } from '@/api'
import type { CategoryVO, ProductVO } from '@/types'

const router = useRouter()

const cats = ref<CategoryVO[]>([])
const catIndex = ref(0)
const categoryId = ref<number | undefined>(undefined)

const keyword = ref('')
const sort = ref('default')
const sortOptions = [
  { text: '综合', value: 'default' },
  { text: '销量', value: 'sales_desc' },
  { text: '价格升序', value: 'price_asc' },
  { text: '价格降序', value: 'price_desc' },
  { text: '最新', value: 'new' }
]

const list = ref<ProductVO[]>([])
const pageNum = ref(1)
const pageSize = 10
const loading = ref(false)
const finished = ref(false)
const refreshing = ref(false)

async function loadPage() {
  loading.value = true
  try {
    const page = await productApi.page({
      pageNum: pageNum.value,
      pageSize,
      categoryId: categoryId.value,
      keyword: keyword.value.trim() || undefined,
      sort: sort.value
    })
    list.value = pageNum.value === 1 ? page.list : [...list.value, ...page.list]
    finished.value = list.value.length >= page.total || page.list.length === 0
    if (!finished.value) pageNum.value += 1
  } catch {
    finished.value = true
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

/** 换条件都要从第 1 页重来 */
function reload() {
  pageNum.value = 1
  list.value = []
  finished.value = false
  loadPage()
}

function onRefresh() {
  reload()
}

function onCatChange(index: number) {
  // 第 0 个是「全部」；选父分类时后端会自动带上所有后代分类
  categoryId.value = index === 0 ? undefined : cats.value[index - 1]?.id
  reload()
}

onMounted(async () => {
  cats.value = await categoryApi.tree()
  reload()
})
</script>

<template>
  <div class="pb-tabbar">
    <van-search v-model="keyword" placeholder="搜商品名 / 副标题" @search="reload" />

    <van-tabs v-model:active="catIndex" shrink @change="onCatChange">
      <van-tab title="全部" />
      <van-tab v-for="c in cats" :key="c.id" :title="c.name" />
    </van-tabs>

    <van-dropdown-menu>
      <van-dropdown-item v-model="sort" :options="sortOptions" @change="reload" />
    </van-dropdown-menu>

    <van-pull-refresh v-model="refreshing" @refresh="onRefresh">
      <van-list
        v-model:loading="loading"
        :finished="finished"
        finished-text="没有更多了"
        :immediate-check="false"
        @load="loadPage"
      >
        <van-card
          v-for="p in list"
          :key="p.id"
          :price="Number(p.price).toFixed(2)"
          :title="p.name"
          :desc="p.subTitle || p.categoryName || ''"
          :thumb="p.mainImage || ''"
          @click="router.push(`/product/${p.id}`)"
        >
          <template #tags>
            <van-tag plain type="primary">销量 {{ p.sales }}</van-tag>
            <van-tag plain :type="p.stock > 0 ? 'success' : 'default'" class="ml">库存 {{ p.stock }}</van-tag>
          </template>
        </van-card>
      </van-list>

      <van-empty v-if="finished && list.length === 0" description="没有找到商品" />
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.ml {
  margin-left: 6px;
}
</style>
