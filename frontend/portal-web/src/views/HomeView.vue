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
  { text: '最相关', value: 'relevance' },
  { text: '销量', value: 'sales_desc' },
  { text: '价格升序', value: 'price_asc' },
  { text: '价格降序', value: 'price_desc' },
  { text: '最新', value: 'new' }
]

// 搜索联想：输入停顿 250ms 再请求，避免每敲一个字打一次接口
const suggestions = ref<string[]>([])
const suggestOpen = ref(false)
let suggestTimer: ReturnType<typeof setTimeout> | undefined

// 空输入时的面板：最近搜索（本地）+ 热门搜索（服务端）
const panelOpen = ref(false)
const hotKeywords = ref<string[]>([])
const HISTORY_KEY = 'mall_search_history'
const history = ref<string[]>(JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]'))

function rememberSearch(kw: string) {
  if (!kw) return
  history.value = [kw, ...history.value.filter((h) => h !== kw)].slice(0, 8)
  localStorage.setItem(HISTORY_KEY, JSON.stringify(history.value))
}

function onFocus() {
  panelOpen.value = true
  if (!hotKeywords.value.length) {
    productApi
      .hotKeywords()
      .then((list) => (hotKeywords.value = list))
      .catch(() => {})
  }
}

function onBlur() {
  // 延迟关闭：点建议项时先触发 blur，立刻关会收不到 click
  setTimeout(() => {
    panelOpen.value = false
  }, 150)
}

function onKeywordInput() {
  const kw = keyword.value.trim()
  clearTimeout(suggestTimer)
  if (!kw) {
    suggestions.value = []
    suggestOpen.value = false
    return
  }
  suggestTimer = setTimeout(async () => {
    suggestions.value = await productApi.suggest(kw).catch(() => [])
    // 请求回来时用户可能已经改了输入，别把旧建议弹出来
    suggestOpen.value = suggestions.value.length > 0 && keyword.value.trim() === kw
  }, 250)
}

function pickSuggestion(text: string) {
  keyword.value = text
  suggestOpen.value = false
  panelOpen.value = false
  suggestions.value = []
  rememberSearch(text)
  reload()
}

function onSearch() {
  suggestOpen.value = false
  panelOpen.value = false
  rememberSearch(keyword.value.trim())
  reload()
}

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
    <div class="search-wrap">
      <van-search
        v-model="keyword"
        placeholder="搜商品名 / 副标题 / 分类"
        @update:model-value="onKeywordInput"
        @focus="onFocus"
        @blur="onBlur"
        @search="onSearch"
        @clear="onSearch"
      />
      <!-- 有输入：联想；空输入：最近搜索 + 热门搜索 -->
      <div v-if="panelOpen && (keyword.trim() ? suggestions.length : history.length || hotKeywords.length)" class="suggest">
        <template v-if="keyword.trim()">
          <div v-for="s in suggestions" :key="s" class="suggest-item" @click="pickSuggestion(s)">
            {{ s }}
          </div>
        </template>
        <template v-else>
          <template v-if="history.length">
            <div class="suggest-title">最近搜索</div>
            <div class="chips">
              <span v-for="h in history" :key="h" class="chip" @click="pickSuggestion(h)">{{ h }}</span>
            </div>
          </template>
          <template v-if="hotKeywords.length">
            <div class="suggest-title">热门搜索</div>
            <div class="chips">
              <span v-for="k in hotKeywords" :key="k" class="chip" @click="pickSuggestion(k)">{{ k }}</span>
            </div>
          </template>
        </template>
      </div>
    </div>

    <!-- 领券入口：以前只能「我的 → 我的优惠券」进去，新用户基本发现不了这张券 -->
    <div class="coupon-entry" @click="router.push('/coupon')">
      <span>领券中心 · 满减券限量发放</span>
      <span class="arrow">›</span>
    </div>

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

.search-wrap {
  position: relative;
  /* van-tabs 的 sticky 用 z-index:99，联想面板必须更高，否则被分类条盖住 */
  z-index: 100;
}

.suggest {
  position: absolute;
  left: 12px;
  right: 12px;
  top: 100%;
  margin-top: -6px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  overflow: hidden;
}

.suggest-item {
  padding: 10px 16px;
  font-size: 14px;
  border-bottom: 1px solid #f5f6f7;
}

.suggest-item:last-child {
  border-bottom: none;
}

.suggest-item:active {
  background: #f7f8fa;
}

.suggest-title {
  padding: 8px 16px 4px;
  color: #969799;
  font-size: 12px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 4px 16px 12px;
}

.chip {
  padding: 4px 12px;
  border-radius: 12px;
  background: #f7f8fa;
  color: #323233;
  font-size: 12px;
}

.chip:active {
  background: #ee0a24;
  color: #fff;
}

.coupon-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 12px 8px;
  padding: 8px 12px;
  border-radius: 8px;
  background: linear-gradient(90deg, #fff1f0, #ffe3db);
  color: #ee0a24;
  font-size: 13px;
}

.coupon-entry .arrow {
  font-size: 16px;
  line-height: 1;
}
</style>
