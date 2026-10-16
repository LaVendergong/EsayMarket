<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import { addressApi } from '@/api'
import type { AddressDTO } from '@/types'

const route = useRoute()
const router = useRouter()

const id = computed(() => {
  const raw = route.params.id
  return raw ? Number(Array.isArray(raw) ? raw[0] : raw) : undefined
})
const isEdit = computed(() => id.value !== undefined)

const saving = ref(false)
const form = reactive<AddressDTO>({
  receiver: '',
  phone: '',
  province: '',
  city: '',
  district: '',
  detail: '',
  isDefault: 0
})

async function load() {
  if (!id.value) return
  const list = await addressApi.list()
  const cur = list.find((a) => a.id === id.value)
  if (cur) {
    Object.assign(form, {
      receiver: cur.receiver,
      phone: cur.phone,
      province: cur.province ?? '',
      city: cur.city ?? '',
      district: cur.district ?? '',
      detail: cur.detail,
      isDefault: cur.isDefault
    })
  }
}

async function submit() {
  if (!form.receiver.trim()) {
    showToast('请填写收货人')
    return
  }
  if (!form.phone.trim()) {
    showToast('请填写联系电话')
    return
  }
  if (!form.detail.trim()) {
    showToast('请填写详细地址')
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await addressApi.update(id.value!, form)
    } else {
      await addressApi.add(form)
    }
    showSuccessToast('保存成功')
    router.back()
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="app-shell">
    <van-nav-bar :title="isEdit ? '编辑地址' : '新增地址'" left-arrow @click-left="router.back()" />

    <van-form @submit="submit">
      <van-cell-group inset class="block">
        <van-field v-model="form.receiver" label="收货人" placeholder="姓名" required />
        <van-field v-model="form.phone" type="tel" label="联系电话" placeholder="手机号" required />
        <van-field v-model="form.province" label="省份" placeholder="选填" />
        <van-field v-model="form.city" label="城市" placeholder="选填" />
        <van-field v-model="form.district" label="区县" placeholder="选填" />
        <van-field
          v-model="form.detail"
          type="textarea"
          rows="2"
          autosize
          label="详细地址"
          placeholder="街道门牌等"
          required
        />
        <van-cell title="设为默认地址">
          <template #right-icon>
            <van-switch :model-value="form.isDefault === 1" size="20" @update:model-value="(v) => (form.isDefault = v ? 1 : 0)" />
          </template>
        </van-cell>
      </van-cell-group>

      <div class="actions">
        <van-button round block type="danger" native-type="submit" :loading="saving">保存</van-button>
      </div>
    </van-form>
  </div>
</template>

<style scoped>
.block {
  margin-top: 12px;
}

.actions {
  padding: 24px 16px;
}
</style>
