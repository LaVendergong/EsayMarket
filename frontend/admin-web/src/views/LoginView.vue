<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { authApi } from '@/api'
import { auth } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const loading = ref(false)

// 演示后台账号（见 sql/V3__seed_demo.sql），默认填好省得手输
const form = reactive({ username: 'malladmin', password: 'admin123456' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const vo = await authApi.login(form.username, form.password)
    auth.set(vo)
    ElMessage.success('登录成功')
    router.replace((route.query.redirect as string) || '/')
  } catch {
    // 错误提示已由 http 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <el-card class="card">
      <h2 class="title">Mall 管理后台</h2>
      <p class="muted sub">仅管理员（role=1）可登录</p>
      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" :prefix-icon="User" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            :prefix-icon="Lock"
            type="password"
            show-password
            placeholder="密码"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="submit">登录</el-button>
      </el-form>
      <p class="muted tip">后端需先启动 mall-admin（http://localhost:8081）</p>
    </el-card>
  </div>
</template>

<style scoped>
.wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #3a5169 100%);
}

.card {
  width: 360px;
  padding: 8px;
}

.title {
  margin: 0 0 4px;
  text-align: center;
}

.sub {
  margin: 0 0 20px;
  text-align: center;
  font-size: 12px;
}

.submit {
  width: 100%;
}

.tip {
  margin: 16px 0 0;
  text-align: center;
  font-size: 12px;
}
</style>
