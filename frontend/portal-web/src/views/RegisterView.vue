<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import { authApi } from '@/api'
import { auth } from '@/stores/auth'

const router = useRouter()
const loading = ref(false)
const form = reactive({ username: '', password: '', nickname: '', phone: '' })

function validate(): string | null {
  if (form.username.length < 4 || form.username.length > 32) return '用户名长度需在 4-32 之间'
  // 与服务端 RegisterDTO 保持一致：8-64 位且同时含字母和数字（纯数字/纯字母太容易被撞库）
  if (form.password.length < 8 || form.password.length > 64) return '密码长度需在 8-64 之间'
  if (!/[A-Za-z]/.test(form.password) || !/\d/.test(form.password)) return '密码需同时包含字母和数字'
  if (form.phone && !/^1[3-9]\d{9}$/.test(form.phone)) return '手机号格式不正确'
  return null
}

async function submit() {
  const err = validate()
  if (err) {
    showToast(err)
    return
  }
  loading.value = true
  try {
    await authApi.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname || form.username,
      phone: form.phone || undefined
    })
    // 注册完直接登录，省一次手输
    const vo = await authApi.login(form.username, form.password)
    auth.set(vo)
    showSuccessToast('注册成功')
    router.replace('/home')
  } catch {
    // 拦截器已提示（用户名重复 -> 1001）
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="app-shell">
    <van-nav-bar title="注册" left-arrow @click-left="router.back()" />

    <van-form @submit="submit">
      <van-cell-group inset>
        <van-field v-model="form.username" label="用户名" placeholder="4-32 位" required />
        <van-field v-model="form.password" type="password" label="密码" placeholder="8-64 位，含字母和数字" required />
        <van-field v-model="form.nickname" label="昵称" placeholder="选填，默认与用户名相同" />
        <van-field v-model="form.phone" type="tel" label="手机号" placeholder="选填" />
      </van-cell-group>
      <div class="actions">
        <van-button round block type="danger" native-type="submit" :loading="loading">注册并登录</van-button>
      </div>
    </van-form>
  </div>
</template>

<style scoped>
.actions {
  padding: 24px 16px;
}
</style>
