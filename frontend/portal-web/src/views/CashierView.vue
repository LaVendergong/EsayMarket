<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showFailToast, showSuccessToast } from 'vant'
import { orderApi, paymentApi } from '@/api'
import type { OrderVO, PayChannelOption, PaymentVO } from '@/types'
import { money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const order = ref<OrderVO | null>(null)
const channels = ref<PayChannelOption[]>([])
const channel = ref('')
const payment = ref<PaymentVO | null>(null)
const loading = ref(false)
const submitting = ref(false)
const paid = ref(false)
const loadFailed = ref(false)
/** 微信扫码时的等待提示 */
const waiting = ref(false)

const orderId = computed(() => Number(route.params.orderId))
const amount = computed(() => Number(order.value?.payAmount || 0))
const currentChannel = computed(() => channels.value.find((c) => c.code === channel.value))

/** 倒计时：订单超时就别再让用户付了 */
const now = ref(Date.now())
let timer: number | undefined
let pollTimer: number | undefined

const remainText = computed(() => {
  const expire = order.value?.expireTime ? new Date(order.value.expireTime).getTime() : 0
  if (!expire) return ''
  const ms = expire - now.value
  if (ms <= 0) return '订单已超时关闭'
  const total = Math.floor(ms / 1000)
  return `支付剩余 ${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
})

async function load() {
  loading.value = true
  try {
    order.value = await orderApi.detail(orderId.value)
    channels.value = await paymentApi.channels()
    if (!channel.value && channels.value.length) {
      channel.value = channels.value[0].code
    }
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

/** 轮询支付单状态：渠道回调是异步的，前端只能问「现在到底付没付」 */
function startPolling(paymentNo: string) {
  stopPolling()
  let times = 0
  pollTimer = window.setInterval(async () => {
    times += 1
    if (times > 60) {
      stopPolling()
      waiting.value = false
      return
    }
    try {
      const p = await paymentApi.detail(paymentNo)
      if (p.status === 1) {
        stopPolling()
        waiting.value = false
        paid.value = true
        payment.value = p
        showSuccessToast('支付成功')
      }
    } catch {
      stopPolling()
    }
  }, 2000)
}

function stopPolling() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

async function submit() {
  if (!channel.value) {
    showFailToast('请选择支付方式')
    return
  }
  submitting.value = true
  try {
    const p = await paymentApi.create(orderId.value, channel.value)
    payment.value = p

    if (p.channel === 'MOCK') {
      // 模拟渠道：直接标记付款成功（仅本地/测试环境可用，prod 里这个渠道根本不会出现）
      await paymentApi.mockPay(p.paymentNo)
      paid.value = true
      showSuccessToast('支付成功')
      return
    }

    if (p.channel === 'ALIPAY_WAP') {
      const payUrl = p.payParams?.payUrl
      if (!payUrl) {
        showFailToast('支付宝未返回支付链接')
        return
      }
      // 跳转支付宝收银台；支付完成后由渠道异步通知我们，用户回来看到的是订单页
      window.location.href = payUrl
      return
    }

    // 微信 Native：展示二维码链接，同时开始轮询
    waiting.value = true
    startPolling(p.paymentNo)
  } finally {
    submitting.value = false
  }
}

async function copyCodeUrl() {
  const url = payment.value?.payParams?.codeUrl
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    showSuccessToast('已复制支付链接')
  } catch {
    showFailToast('复制失败，请手动选择文本')
  }
}

onMounted(() => {
  load()
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
  stopPolling()
})
</script>

<template>
  <div class="app-shell">
    <van-nav-bar title="收银台" left-arrow @click-left="router.back()" />

    <van-empty v-if="!order" :description="loadFailed ? '订单不存在' : '加载中…'" />

    <template v-else>
      <!-- 支付成功 -->
      <div v-if="paid" class="done">
        <div class="done-icon">✓</div>
        <div class="done-title">支付成功</div>
        <div class="done-amount">{{ money(amount) }}</div>
        <div class="done-actions">
          <van-button round plain @click="router.replace('/orders')">查看订单</van-button>
          <van-button round type="danger" @click="router.replace('/home')">继续逛逛</van-button>
        </div>
      </div>

      <template v-else>
        <div class="amount-box">
          <div class="label">需支付</div>
          <div class="amount">{{ money(amount) }}</div>
          <div class="muted">订单号 {{ order.orderNo }}</div>
          <div class="countdown">{{ remainText }}</div>
        </div>

        <div class="section-title">选择支付方式</div>
        <van-cell-group inset>
          <van-radio-group v-model="channel">
            <van-cell
              v-for="c in channels"
              :key="c.code"
              clickable
              :title="c.text"
              @click="channel = c.code"
            >
              <template #right-icon>
                <van-radio :name="c.code" />
              </template>
            </van-cell>
          </van-radio-group>
        </van-cell-group>

        <van-empty v-if="!channels.length" description="当前没有可用的支付方式（请检查渠道配置）" />

        <!-- 微信扫码：显示 code_url，用户用微信扫这个链接 -->
        <div v-if="payment && payment.channel === 'WECHAT_NATIVE'" class="qrcode">
          <div class="qr-title">请用微信扫描下方链接完成支付</div>
          <div class="qr-url">{{ payment.payParams?.codeUrl }}</div>
          <van-button size="small" round @click="copyCodeUrl">复制支付链接</van-button>
          <div v-if="waiting" class="muted waiting">等待支付结果…（渠道回调到达后自动刷新）</div>
        </div>

        <div class="tips">
          <div v-if="currentChannel?.code === 'MOCK'">
            模拟支付：点「确认支付」立即标记为已支付，仅用于本地与自动化测试。
          </div>
          <div v-else-if="currentChannel?.code === 'ALIPAY_WAP'">将跳转到支付宝收银台完成付款。</div>
          <div v-else-if="currentChannel?.code === 'WECHAT_NATIVE'">生成支付链接后，用微信扫码付款。</div>
          <div>支付结果以渠道异步通知为准，本页会自动刷新状态。</div>
        </div>

        <van-submit-bar
          :price="Math.round(amount * 100)"
          :button-text="`确认支付`"
          :loading="submitting"
          :disabled="!channels.length"
          @submit="submit"
        />
      </template>
    </template>
  </div>
</template>

<style scoped>
.amount-box {
  margin: 12px;
  padding: 20px 16px;
  background: #fff;
  border-radius: 10px;
  text-align: center;
}

.label {
  font-size: 13px;
  color: #969799;
}

.amount {
  margin: 6px 0;
  font-size: 30px;
  font-weight: 700;
  color: #ee0a24;
}

.muted {
  font-size: 12px;
  color: #969799;
}

.countdown {
  margin-top: 8px;
  font-size: 12px;
  color: #ff976a;
}

.section-title {
  padding: 14px 16px 6px;
  font-size: 13px;
  color: #969799;
}

.tips {
  margin: 16px;
  font-size: 12px;
  color: #969799;
  line-height: 1.8;
}

.qrcode {
  margin: 12px 16px;
  padding: 16px;
  background: #fff;
  border-radius: 10px;
  text-align: center;
}

.qr-title {
  font-size: 13px;
  color: #323233;
  padding-bottom: 10px;
}

.qr-url {
  word-break: break-all;
  font-size: 12px;
  color: #646566;
  background: #f7f8fa;
  border-radius: 6px;
  padding: 10px;
  margin-bottom: 10px;
}

.waiting {
  margin-top: 10px;
}

.done {
  margin-top: 60px;
  text-align: center;
}

.done-icon {
  width: 68px;
  height: 68px;
  line-height: 68px;
  margin: 0 auto;
  border-radius: 50%;
  background: #07c160;
  color: #fff;
  font-size: 34px;
}

.done-title {
  margin-top: 16px;
  font-size: 18px;
  font-weight: 600;
}

.done-amount {
  margin-top: 6px;
  font-size: 24px;
  color: #ee0a24;
  font-weight: 700;
}

.done-actions {
  margin-top: 28px;
  display: flex;
  justify-content: center;
  gap: 12px;
}
</style>
