<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showSuccessToast, showToast } from 'vant'
import type { UploaderFileListItem } from 'vant'
import { reviewApi, uploadApi } from '@/api'

const route = useRoute()
const router = useRouter()

// 只需要 orderItemId：商品/订单都由服务端反查，query 里的 name/spec 只用于展示
const orderItemId = Number(route.params.orderItemId)
const productName = computed(() => String(route.query.name || '商品'))
const spec = computed(() => String(route.query.spec || ''))

const rating = ref(5)
const content = ref('')
const anonymous = ref(false)
const submitting = ref(false)

/** 晒图：图片先传到服务端换 URL，评价里只存 URL（换对象存储时前端不用改） */
const files = ref<UploaderFileListItem[]>([])

async function afterRead(item: UploaderFileListItem | UploaderFileListItem[]) {
  const list = Array.isArray(item) ? item : [item]
  for (const f of list) {
    if (!f.file) continue
    f.status = 'uploading'
    f.message = '上传中'
    try {
      const form = new FormData()
      form.append('file', f.file)
      f.url = await uploadApi.image(form)
      f.status = 'done'
      f.message = ''
    } catch {
      f.status = 'failed'
      f.message = '上传失败'
    }
  }
}

function removeRead(item: UploaderFileListItem, detail: { index: number }) {
  files.value.splice(detail.index, 1)
}

async function submit() {
  if (!rating.value) {
    showToast('请选择星级')
    return
  }
  if (files.value.some((f) => f.status === 'uploading')) {
    showToast('图片还在上传，请稍候')
    return
  }
  submitting.value = true
  try {
    const images = files.value
      .filter((f) => f.status === 'done' && f.url)
      .map((f) => f.url as string)
    await reviewApi.submit({
      orderItemId,
      rating: rating.value,
      content: content.value.trim() || undefined,
      anonymous: anonymous.value ? 1 : 0,
      images
    })
    showSuccessToast('评价成功')
    router.back()
  } catch {
    // 拦截器已提示（未完成 / 重复评价）
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="app-shell">
    <van-nav-bar title="评价商品" left-arrow @click-left="router.back()" />

    <van-cell-group inset class="block">
      <van-cell :title="productName" :label="spec || undefined" />
    </van-cell-group>

    <van-cell-group inset class="block">
      <van-cell title="评分">
        <template #value>
          <van-rate v-model="rating" :size="22" color="#ee0a24" void-color="#e5e5e5" />
        </template>
      </van-cell>
      <van-field
        v-model="content"
        type="textarea"
        rows="4"
        maxlength="500"
        show-word-limit
        placeholder="说说这件商品怎么样（选填）"
      />
      <van-cell title="晒图" label="最多 3 张，单张不超过 2MB（jpg/png/webp）">
        <template #value>
          <van-uploader
            v-model="files"
            :max-count="3"
            :after-read="afterRead"
            accept="image/jpeg,image/png,image/webp"
            @delete="removeRead"
          />
        </template>
      </van-cell>
      <van-cell title="匿名评价" label="开启后别人看到的是「匿名用户」">
        <template #right-icon>
          <van-switch v-model="anonymous" size="20" />
        </template>
      </van-cell>
    </van-cell-group>

    <div class="actions">
      <van-button block round type="danger" :loading="submitting" @click="submit">提交评价</van-button>
    </div>
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
