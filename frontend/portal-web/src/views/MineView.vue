<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast } from 'vant'
import { authApi, userApi } from '@/api'
import { auth } from '@/stores/auth'
import { cart } from '@/stores/cart'
import type { UserVO } from '@/types'

const router = useRouter()
const me = ref<UserVO | null>(null)

onMounted(async () => {
  if (!auth.isLoggedIn()) return
  try {
    me.value = await userApi.me()
  } catch {
    // 拦截器已提示
  }
})

async function logout() {
  try {
    await showConfirmDialog({ title: '提示', message: '确定退出登录？' })
  } catch {
    return
  }
  // 先让服务端把 HttpOnly Cookie 清掉（前端删不掉它）
  await authApi.logout().catch(() => {})
  auth.clear()
  cart.clear()
  me.value = null
  showSuccessToast('已退出')
  router.replace('/home')
}

function goto(path: string) {
  if (!auth.isLoggedIn()) {
    router.push({ name: 'login', query: { redirect: path } })
    return
  }
  router.push(path)
}
</script>

<template>
  <div class="pb-tabbar">
    <div class="profile">
      <template v-if="auth.isLoggedIn()">
        <div class="avatar">{{ (me?.nickname || me?.username || 'U').slice(0, 1).toUpperCase() }}</div>
        <div>
          <div class="nick">{{ me?.nickname || me?.username }}</div>
          <div class="muted small">{{ me?.phone || '未绑定手机号' }}</div>
        </div>
      </template>
      <template v-else>
        <div class="avatar">?</div>
        <div>
          <div class="nick">未登录</div>
          <div class="muted small">登录后可加购与下单</div>
        </div>
        <van-button size="small" type="danger" round class="login" @click="router.push('/login')">去登录</van-button>
      </template>
    </div>

    <van-cell-group inset class="block">
      <van-cell title="我的订单" is-link @click="goto('/orders')" />
      <van-cell title="我的售后" is-link @click="goto('/after-sale')" />
      <van-cell title="收货地址" is-link @click="goto('/address')" />
      <van-cell title="购物车" is-link @click="goto('/cart')" />
    </van-cell-group>

    <van-cell-group v-if="auth.isLoggedIn()" inset class="block">
      <van-cell title="退出登录" is-link @click="logout" />
    </van-cell-group>

    <div class="foot muted">Mall 商城 · 演示环境（支付为模拟）</div>
  </div>
</template>

<style scoped>
.profile {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 28px 20px;
  background: linear-gradient(135deg, #ff6034 0%, #ee0a24 100%);
  color: #fff;
}

.avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-weight: 700;
}

.nick {
  font-size: 17px;
  font-weight: 600;
}

.profile .muted {
  color: rgba(255, 255, 255, 0.8);
}

.small {
  font-size: 12px;
  margin-top: 4px;
}

.login {
  margin-left: auto;
}

.block {
  margin-top: 12px;
}

.foot {
  padding: 24px;
  text-align: center;
  font-size: 12px;
}
</style>
