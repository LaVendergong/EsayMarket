<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog } from 'vant'
import { digitalApi } from '@/api'
import type { DigitalGrantVO } from '@/types'

const router = useRouter()
const grants = ref<DigitalGrantVO[]>([])
const contents = ref<Record<number, string>>({})

function fmt(v?: string) {
  return v ? v.replace('T', ' ').slice(0, 16) : ''
}

/** 卡密内容是「卡号|卡密」，分两行显示 */
function parts(content: string) {
  const [no, secret] = content.split('|')
  return { no, secret }
}

async function load() {
  grants.value = await digitalApi.mine().catch(() => [])
}

async function reveal(g: DigitalGrantVO) {
  try {
    await showConfirmDialog({
      title: '查看凭证',
      message: '查看后视为已使用，该订单将不支持自助退款'
    })
  } catch {
    return
  }
  try {
    const r = await digitalApi.reveal(g.id)
    contents.value = { ...contents.value, [g.id]: r.content }
    await load()
  } catch {
    // 拦截器已提示
  }
}

onMounted(load)
</script>

<template>
  <div class="app-shell">
    <van-nav-bar title="我的卡券" left-arrow @click-left="router.back()" />

    <div v-if="!grants.length" class="empty">
      <van-empty description="还没有虚拟商品，去逛逛吧">
        <van-button round type="danger" size="small" @click="router.push('/home')">去首页</van-button>
      </van-empty>
    </div>

    <div v-else class="list">
      <div v-for="g in grants" :key="g.id" class="card" :class="{ dim: g.status !== 1 }">
        <div class="head">
          <span class="name">{{ g.productName }}</span>
          <van-tag :type="g.status === 1 ? 'danger' : 'default'">{{ g.statusText }}</van-tag>
        </div>
        <div class="muted meta">
          {{ g.deliveryTypeText }} · {{ g.spec || '默认' }} · {{ fmt(g.grantTime) }}
        </div>
        <div class="muted meta">已查看 {{ g.viewCount }} 次</div>

        <div v-if="contents[g.id]" class="content">
          <template v-if="g.deliveryType === 1">
            <div>卡号：{{ parts(contents[g.id]).no }}</div>
            <div>卡密：{{ parts(contents[g.id]).secret }}</div>
          </template>
          <div v-else>{{ contents[g.id] }}</div>
        </div>

        <div class="ops">
          <van-button v-if="g.status === 1" size="small" type="danger" @click="reveal(g)">
            {{ contents[g.id] ? '已显示' : '查看' }}
          </van-button>
          <van-button size="small" plain @click="router.push(`/order/${g.orderId}`)">看订单</van-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.list {
  padding: 12px;
}

.card {
  background: #fff;
  border-radius: 10px;
  padding: 14px;
  margin-bottom: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.card.dim {
  opacity: 0.6;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.name {
  flex: 1;
  font-weight: 600;
}

.meta {
  font-size: 12px;
  line-height: 1.7;
}

.content {
  margin-top: 8px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #fff7f0;
  font-family: monospace;
  font-size: 14px;
  word-break: break-all;
}

.ops {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.muted {
  color: #969799;
}

.empty {
  padding-top: 24px;
}
</style>
