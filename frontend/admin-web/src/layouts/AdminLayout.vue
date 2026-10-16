<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Files, Goods, Tickets } from '@element-plus/icons-vue'
import { auth } from '@/stores/auth'

const route = useRoute()
const router = useRouter()

const me = computed(() => auth.user.value)
const title = computed(() => (route.meta.title as string) || '')

// 新增/编辑商品这类子页面也要把侧边栏的「商品管理」点亮
const activeMenu = computed(() => {
  const p = route.path
  if (p.startsWith('/product')) return '/product'
  if (p.startsWith('/category')) return '/category'
  if (p.startsWith('/order')) return '/order'
  return p
})

async function onCommand(cmd: string) {
  if (cmd !== 'logout') return
  try {
    await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' })
  } catch {
    return
  }
  auth.clear()
  ElMessage.success('已退出')
  router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">Mall 管理后台</div>
      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item index="/product">
          <el-icon><Goods /></el-icon>
          <span>商品管理</span>
        </el-menu-item>
        <el-menu-item index="/category">
          <el-icon><Files /></el-icon>
          <span>分类管理</span>
        </el-menu-item>
        <el-menu-item index="/order">
          <el-icon><Tickets /></el-icon>
          <span>订单管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="page-title">{{ title }}</div>
        <div class="spacer" />
        <el-dropdown @command="onCommand">
          <span class="user">
            {{ me?.nickname || me?.username || '未登录' }}
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>

      <el-main class="main">
        <router-view :key="route.fullPath" />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  background: #1f2d3d;
  display: flex;
  flex-direction: column;
}

.logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-weight: 600;
  letter-spacing: 1px;
  background: #16222e;
}

.menu {
  border-right: none;
  flex: 1;
}

.header {
  display: flex;
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.user {
  cursor: pointer;
  color: #409eff;
  outline: none;
}

.main {
  background: #f5f7fa;
  padding: 0;
}
</style>
