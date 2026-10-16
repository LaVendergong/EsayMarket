<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import { authApi } from '@/api'
import { auth } from '@/stores/auth'
import { cart } from '@/stores/cart'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    showToast('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const vo = await authApi.login(form.username, form.password)
    auth.set(vo)
    await cart.refresh()
    showSuccessToast('登录成功')
    router.replace((route.query.redirect as string) || '/home')
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="app-shell">
    <van-nav-bar title="登录" left-arrow @click-left="router.replace('/home')" />

    <div class="hero">
      <div class="logo">Mall 商城</div>
      <div class="muted">登录后才能加购、下单</div>
    </div>

    <van-form @submit="submit">
      <van-cell-group inset>
        <van-field
          v-model="form.username"
          name="username"
          label="用户名"
          placeholder="请输入用户名"
          autocomplete="username"
        />
        <van-field
          v-model="form.password"
          type="password"
          name="password"
          label="密码"
          placeholder="请输入密码"
          autocomplete="current-password"
        />
      </van-cell-group>
      <div class="actions">
        <van-button round block type="danger" native-type="submit" :loading="loading">登录</van-button>
        <van-button round block plain type="danger" class="mt" @click="router.push('/register')">去注册</van-button>
      </div>
    </van-form>
  </div>
</template>

<style scoped>
.hero {
  padding: 32px 16px 24px;
  text-align: center;
}

.logo {
  font-size: 22px;
  font-weight: 700;
  margin-bottom: 6px;
}

.actions {
  padding: 24px 16px;
}

.mt {
  margin-top: 12px;
}
</style>
