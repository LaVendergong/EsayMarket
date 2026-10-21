<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast, showToast } from 'vant'
import { cartApi, orderApi } from '@/api'
import { cart } from '@/stores/cart'
import { ORDER_TABS, type OrderVO } from '@/types'
import { money } from '@/utils/format'

const router = useRouter()

const activeTab = ref(0)
const status = computed(() => {
  const name = ORDER_TABS[activeTab.value]?.name
  return name === '' || name === undefined ? undefined : Number(name)
})

const list = ref<OrderVO[]>([])
const pageNum = ref(1)
const pageSize = 5
const loading = ref(false)
const finished = ref(false)
const refreshing = ref(false)

async function loadPage() {
  loading.value = true
  try {
    const page = await orderApi.page({ pageNum: pageNum.value, pageSize, status: status.value })
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

function reload() {
  pageNum.value = 1
  list.value = []
  finished.value = false
  expired.clear()
  loadPage()
}

async function pay(o: OrderVO) {
  await orderApi.pay(o.id)
  showSuccessToast('支付成功')
  reload()
}

async function cancel(o: OrderVO) {
  try {
    await showConfirmDialog({ title: '取消订单', message: '取消后会释放该订单锁定的库存，确定？' })
  } catch {
    return
  }
  await orderApi.cancel(o.id)
  showSuccessToast('已取消')
  reload()
}

async function confirm(o: OrderVO) {
  try {
    await showConfirmDialog({ title: '确认收货', message: '确认已收到货？' })
  } catch {
    return
  }
  await orderApi.confirm(o.id)
  showSuccessToast('交易完成')
  reload()
}

/**
 * 再次购买：把订单里的商品按原数量重新加进购物车。
 *
 * 逐条加（购物车接口是单品维度），任何一条失败都不中断其它条目 ——
 * 常见情况是「其中某个 SKU 已下架」，这时给出提示但其余仍然加成功，
 * 比整体失败让用户重新挑要好。
 */
async function rebuy(o: OrderVO) {
  let added = 0
  let failed = 0
  for (const item of o.items) {
    try {
      await cartApi.add(item.skuId, item.quantity)
      added += 1
    } catch {
      failed += 1
    }
  }
  await cart.refresh()
  if (!added) {
    showToast('商品已下架，无法再次购买')
    return
  }
  showSuccessToast(failed ? `已加入 ${added} 件，${failed} 件已下架` : '已加入购物车')
  router.push('/cart')
}

// ---------------------------------------------------------------- 待支付倒计时
/**
 * 主流电商都会在"待支付"订单上显示剩余时间（我们后端本来就有 expireTime，
 * 超时会由定时任务关单）。这里每秒刷新一次文本，**只在有未支付订单时才跑定时器**。
 */
const now = ref(Date.now())
const expired = new Set<number>()
let timer: number | undefined

function stopTicker() {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function startTicker() {
  if (timer) return
  timer = window.setInterval(() => {
    now.value = Date.now()
    onTick()
  }, 1000)
}

function remainMs(o: OrderVO): number {
  if (!o.expireTime) return 0
  return new Date(o.expireTime).getTime() - now.value
}

function remainText(o: OrderVO): string {
  const ms = remainMs(o)
  if (ms <= 0) return '已超时'
  const total = Math.floor(ms / 1000)
  const m = String(Math.floor(total / 60)).padStart(2, '0')
  const s = String(total % 60).padStart(2, '0')
  return `剩 ${m}:${s} 自动关闭`
}

/** 倒计时归零时刷新一次列表，让"已关闭"及时反映出来（每个订单只触发一次） */
function onTick() {
  for (const o of list.value) {
    if (o.status === 0 && remainMs(o) <= 0 && !expired.has(o.id)) {
      expired.add(o.id)
      reload()
      return
    }
  }
}

onMounted(() => {
  reload()
  startTicker()
})

onBeforeUnmount(stopTicker)
</script>

<template>
  <div class="app-shell">
    <van-nav-bar title="我的订单" left-arrow @click-left="router.back()" />

    <van-tabs v-model:active="activeTab" shrink @change="reload">
      <van-tab v-for="t in ORDER_TABS" :key="t.name" :title="t.label" />
    </van-tabs>

    <van-pull-refresh v-model="refreshing" @refresh="reload">
      <van-list
        v-model:loading="loading"
        :finished="finished"
        finished-text="没有更多了"
        :immediate-check="false"
        @load="loadPage"
      >
        <div v-for="o in list" :key="o.id" class="order" @click="router.push(`/order/${o.id}`)">
          <div class="ohead">
            <span class="muted">{{ o.orderNo }}</span>
            <span class="status">{{ o.statusText }}</span>
          </div>

          <div v-for="it in o.items" :key="it.id" class="oitem">
            <van-image :src="it.productImage || ''" width="48" height="48" fit="cover" />
            <div class="oinfo">
              <div class="oname">{{ it.productName }}</div>
              <div class="muted">{{ it.spec }} × {{ it.quantity }}</div>
            </div>
            <div>{{ money(it.totalAmount) }}</div>
          </div>

          <div v-if="o.status === 0 && o.expireTime" class="countdown">
            {{ remainText(o) }}
          </div>

          <div class="ofoot">
            <span>实付 <b class="price">{{ money(o.payAmount) }}</b></span>
            <div class="oacts" @click.stop>
              <van-button v-if="o.status === 0" size="small" plain round @click="cancel(o)">取消</van-button>
              <van-button v-if="o.status === 0" size="small" type="danger" round @click="pay(o)">去支付</van-button>
              <van-button v-if="o.status === 2" size="small" type="danger" round @click="confirm(o)">确认收货</van-button>
              <van-button v-if="o.status === 3 || o.status === 4" size="small" plain round @click="rebuy(o)">
                再次购买
              </van-button>
            </div>
          </div>
        </div>
      </van-list>

      <van-empty v-if="finished && list.length === 0" description="还没有订单" />
    </van-pull-refresh>
  </div>
</template>

<style scoped>
.order {
  background: #fff;
  margin: 10px 12px;
  border-radius: 8px;
  padding: 12px;
}

.ohead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f2f3f5;
}

.status {
  color: #ee0a24;
}

.oitem {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
}

.oinfo {
  flex: 1;
  min-width: 0;
}

.oname {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.countdown {
  padding: 4px 0;
  font-size: 12px;
  color: #ff976a;
}

.ofoot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid #f2f3f5;
  font-size: 12px;
}

.oacts {
  display: flex;
  gap: 8px;
}
</style>
