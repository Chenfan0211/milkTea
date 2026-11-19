<script setup lang="ts">
defineOptions({ name: 'ProductDetailImages' });

import { computed, ref } from 'vue';
import { NButton, NImage, NUpload } from 'naive-ui';
import type { UploadCustomRequestOptions } from 'naive-ui';
import { uploadAdminImage } from '@/service/api/crud';
import { ADMIN_IMAGE_FALLBACK, useAdminImageUrl } from '@/views/_shared/use-admin-image-url';

const MAX_DETAIL_IMAGES = 20;
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

const props = withDefaults(
  defineProps<{
    modelValue: string[];
    disabled?: boolean;
  }>(),
  {
    disabled: false
  }
);
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>();
const adminImageUrl = useAdminImageUrl();
const uploading = ref(false);
const images = computed(() => (Array.isArray(props.modelValue) ? props.modelValue : []));
const reachedLimit = computed(() => images.value.length >= MAX_DETAIL_IMAGES);

function update(next: string[]) {
  emit('update:modelValue', next);
}

function validateUpload(file: File) {
  const name = String(file.name || '').toLowerCase();
  const validType = ['image/jpeg', 'image/png'].includes(file.type);
  const validExt = name.endsWith('.jpg') || name.endsWith('.jpeg') || name.endsWith('.png');
  if (!validType && !validExt) return '仅支持 JPG 或 PNG 图片';
  if (file.size > MAX_UPLOAD_BYTES) return '单张详情长图不能超过 5MB';
  if (reachedLimit.value) return `详情长图最多 ${MAX_DETAIL_IMAGES} 张`;
  return '';
}

async function handleUpload(options: UploadCustomRequestOptions) {
  const file = options.file.file;
  if (!(file instanceof File)) {
    options.onError();
    return;
  }
  const errorMessage = validateUpload(file);
  if (errorMessage) {
    options.onError();
    window.$message?.warning(errorMessage);
    return;
  }

  uploading.value = true;
  try {
    const result = await uploadAdminImage(file);
    if (!images.value.includes(result.url)) {
      update(images.value.concat(result.url));
    }
    options.onFinish();
    window.$message?.success('详情长图上传成功');
  } catch (error: any) {
    options.onError();
    window.$message?.error(error?.message || '详情长图上传失败');
  } finally {
    uploading.value = false;
  }
}

function removeImage(index: number) {
  update(images.value.filter((_, current) => current !== index));
}

function moveImage(index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= images.value.length) return;
  const next = images.value.slice();
  [next[index], next[target]] = [next[target], next[index]];
  update(next);
}
</script>

<template>
  <div class="detail-images-editor">
    <div v-if="images.length" class="detail-images-list">
      <div v-for="(image, index) in images" :key="`${image}-${index}`" class="detail-image-row">
        <NImage
          :src="adminImageUrl(image)"
          :fallback-src="ADMIN_IMAGE_FALLBACK"
          :width="160"
          :height="120"
          object-fit="cover"
          :preview-disabled="false"
          class="detail-image-preview"
        />
        <div class="detail-image-meta">
          <span class="detail-image-order">第 {{ index + 1 }} 张</span>
          <div class="detail-image-actions">
            <NButton size="tiny" :disabled="disabled || index === 0" @click="moveImage(index, -1)">上移</NButton>
            <NButton size="tiny" :disabled="disabled || index === images.length - 1" @click="moveImage(index, 1)">
              下移
            </NButton>
            <NButton size="tiny" type="error" quaternary :disabled="disabled" @click="removeImage(index)">移除</NButton>
          </div>
        </div>
      </div>
    </div>
    <div v-else class="detail-images-empty">暂无详情长图</div>
    <NUpload
      accept=".jpg,.jpeg,.png,image/jpeg,image/png"
      :show-file-list="false"
      :custom-request="handleUpload"
      :disabled="disabled || uploading || reachedLimit"
    >
      <NButton dashed type="primary" :loading="uploading" :disabled="disabled || reachedLimit">添加详情长图</NButton>
    </NUpload>
    <div class="detail-images-hint">
      最多 {{ MAX_DETAIL_IMAGES }} 张，按从上到下顺序展示；JPG/PNG 单张不超过 5MB，超过 500KB 自动压缩。
    </div>
  </div>
</template>

<style scoped>
.detail-images-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.detail-images-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.detail-image-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid #e8e8e4;
  border-radius: 10px;
  background: #fafaf8;
}

.detail-image-preview {
  flex: none;
  overflow: hidden;
  border-radius: 8px;
}

.detail-image-meta {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.detail-image-order {
  color: #666762;
  font-weight: 600;
}

.detail-image-actions {
  display: flex;
  gap: 8px;
}

.detail-images-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 88px;
  border: 1px dashed #d9d9d3;
  border-radius: 10px;
  color: #9b9b96;
  background: #fafaf8;
}

.detail-images-hint {
  color: #9b9b96;
  font-size: 12px;
}
</style>
