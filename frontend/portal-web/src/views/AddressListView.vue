<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showSuccessToast } from 'vant'
import { addressApi } from '@/api'
import type { AddressVO } from '@/types'

const router = useRouter()
const list = ref<AddressVO[]>([])
const loading = ref(false)

const fullAddress = (a: AddressVO) => [a.province, a.city, a.district, a.detail].filter(Boolean).join('')

async function load() {
  loading.value = true
  try {
    list.value = await addressApi.list()
  } finally {
    loading.value = false
  }
}

async function setDefault(a: AddressVO) {
  if (a.isDefault === 1) return
  await addressApi.setDefault(a.id)
  showSuccessToast('已设为默认')
  await load()
}

async function remove(a: AddressVO) {
  try {
    await showConfirmDialog({ title: '提示', message: `确定删除「${a.receiver}」的地址？` })
  } catch {
    return
  }
  await addressApi.remove(a.id)
  showSuccessToast('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div class="app-shell pb-submit">
    <van-nav-bar title="收货地址" left-arrow @click-left="router.back()" />

    <van-loading v-if="loading && !list.length" class="loading" />

    <van-cell-group v-for="a in list" :key="a.id" inset class="block">
      <van-cell clickable @click="setDefault(a)">
        <template #title>
          <div class="row">
            <span>{{ a.receiver }}</span>
            <span class="muted">{{ a.phone }}</span>
            <van-tag v-if="a.isDefault === 1" type="danger" plain>默认</van-tag>
          </div>
          <div class="muted addr">{{ fullAddress(a) }}</div>
        </template>
      </van-cell>
      <van-cell>
        <template #title>
          <div class="acts">
            <van-button size="small" plain round @click="router.push(`/address/${a.id}/edit`)">编辑</van-button>
            <van-button size="small" plain round type="danger" @click="remove(a)">删除</van-button>
            <van-button v-if="a.isDefault !== 1" size="small" plain round @click="setDefault(a)">设为默认</van-button>
          </div>
        </template>
      </van-cell>
    </van-cell-group>

    <van-empty v-if="!loading && !list.length" description="还没有收货地址" />

    <div class="footer">
      <van-button round block type="danger" @click="router.push('/address/new')">新增地址</van-button>
    </div>
  </div>
</template>

<style scoped>
.block {
  margin-top: 12px;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.addr {
  margin-top: 4px;
  white-space: normal;
}

.acts {
  display: flex;
  gap: 8px;
}

.loading {
  display: block;
  margin: 40px auto;
}

.footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  max-width: 640px;
  margin: 0 auto;
  padding: 10px 16px;
  background: #fff;
  border-top: 1px solid #ebedf0;
}
</style>
