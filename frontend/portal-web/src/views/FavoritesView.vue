<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import { favoriteApi } from '@/api'
import type { FavoriteVO } from '@/types'
import { money } from '@/utils/format'

const router = useRouter()
const list = ref<FavoriteVO[]>([])
const loading = ref(false)

const total = ref(0)
const hasMore = computed(() => list.value.length < total.value)
let pageNum = 1

async function load(reset = false) {
  if (loading.value) return
  loading.value = true
  try {
    const p = await favoriteApi.page(reset ? 1 : pageNum, 10)
    list.value = reset ? p.list : [...list.value, ...p.list]
    total.value = p.total
    pageNum = reset ? 1 : pageNum
  } finally {
    loading.value = false
  }
}

function loadMore() {
  pageNum += 1
  load()
}

async function remove(item: FavoriteVO) {
  try {
    await favoriteApi.toggle(item.productId)
    list.value = list.value.filter((f) => f.id !== item.id)
    total.value = Math.max(total.value - 1, 0)
    showSuccessToast('已取消收藏')
  } catch {
    // 拦截器已提示
  }
}

function gotoProduct(item: FavoriteVO) {
  if (item.status === 0) {
    showToast('该商品已下架')
    return
  }
  router.push(`/product/${item.productId}`)
}

onMounted(() => load(true))
</script>

<template>
  <div class="app-shell">
    <van-nav-bar title="我的收藏" left-arrow @click-left="router.back()" />

    <div v-if="!list.length && !loading" class="empty">
      <van-empty description="还没有收藏的商品">
        <van-button round type="danger" size="small" @click="router.push('/home')">去逛逛</van-button>
      </van-empty>
    </div>

    <div v-else class="list">
      <div v-for="f in list" :key="f.id" class="item" :class="{ dim: f.status === 0 }">
        <van-image
          :src="f.mainImage || ''"
          width="88"
          height="88"
          fit="cover"
          radius="6"
          @click="gotoProduct(f)"
        />
        <div class="info" @click="gotoProduct(f)">
          <div class="name">{{ f.productName }}</div>
          <div class="muted">{{ f.subTitle || '' }}</div>
          <div class="price-row">
            <span class="price">{{ money(f.price || 0) }}</span>
            <van-tag v-if="f.status === 0" type="default">已下架</van-tag>
            <van-tag v-else-if="(f.stock ?? 0) <= 0" type="warning">无货</van-tag>
          </div>
        </div>
        <van-button size="small" plain class="remove" @click="remove(f)">取消</van-button>
      </div>

      <div v-if="hasMore" class="more">
        <van-button block plain size="small" :loading="loading" @click="loadMore">加载更多</van-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.list {
  padding: 12px;
}

.item {
  display: flex;
  gap: 10px;
  align-items: center;
  background: #fff;
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.item.dim {
  opacity: 0.6;
}

.info {
  flex: 1;
  min-width: 0;
}

.name {
  font-weight: 600;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.price-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.price {
  color: #ee0a24;
  font-weight: 600;
}

.remove {
  flex: none;
}

.more {
  padding: 8px 0 24px;
}

.muted {
  color: #969799;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty {
  padding-top: 24px;
}
</style>
