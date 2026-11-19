<script setup lang="ts">
defineOptions({ name: 'ProductGalleryImages' });

import { computed, ref } from 'vue';
import { NButton, NImage, NTag, NUpload } from 'naive-ui';
import type { UploadCustomRequestOptions } from 'naive-ui';
import { uploadAdminImage } from '@/service/api/crud';
import { ADMIN_IMAGE_FALLBACK, useAdminImageUrl } from '@/views/_shared/use-admin-image-url';

const MAX_GALLERY_IMAGES = 10;
const MAX_SOURCE_BYTES = 5 * 1024 * 1024;

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
const reachedLimit = computed(() => images.value.length >= MAX_GALLERY_IMAGES);

function update(next: string[]) {
  emit('update:modelValue', next);
}

function validateUpload(file: File) {
  const name = String(file.name || '').toLowerCase();
  const validType = ['image/jpeg', 'image/png'].includes(file.type);
  const validExt = name.endsWith('.jpg') || name.endsWith('.jpeg') || name.endsWith('.png');
  if (!validType && !validExt) return '仅支持 JPG 或 PNG 图片';
  if (file.size > MAX_SOURCE_BYTES) return '单张商品主图不能超过 5MB';
  if (reachedLimit.value) return `商品主图最多 ${MAX_GALLERY_IMAGES} 张`;
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
    window.$message?.success('商品主图上传成功');
  } catch (error: any) {
    options.onError();
    window.$message?.error(error?.message || '商品主图上传失败');
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
  <div class="gallery-editor">
    <div v-if="images.length" class="gallery-list">
      <div v-for="(image, index) in images" :key="`${image}-${index}`" class="gallery-row">
        <NImage
          :src="adminImageUrl(image)"
          :fallback-src="ADMIN_IMAGE_FALLBACK"
          :width="104"
          :height="104"
          object-fit="cover"
          :preview-disabled="false"
          class="gallery-preview"
        />
        <div class="gallery-meta">
          <div class="gallery-title-row">
            <span>第 {{ index + 1 }} 张</span>
            <NTag v-if="index === 0" size="tiny" type="success" :bordered="false">封面</NTag>
          </div>
          <div class="gallery-actions">
            <NButton size="tiny" :disabled="disabled || index === 0" @click="moveImage(index, -1)">上移</NButton>
            <NButton size="tiny" :disabled="disabled || index === images.length - 1" @click="moveImage(index, 1)">
              下移
            </NButton>
            <NButton size="tiny" type="error" quaternary :disabled="disabled" @click="removeImage(index)">移除</NButton>
          </div>
        </div>
      </div>
    </div>
    <div v-else class="gallery-empty">暂无商品主图</div>
    <NUpload
      accept=".jpg,.jpeg,.png,image/jpeg,image/png"
      :show-file-list="false"
      :custom-request="handleUpload"
      :disabled="disabled || uploading || reachedLimit"
    >
      <NButton dashed type="primary" :loading="uploading" :disabled="disabled || reachedLimit">添加商品主图</NButton>
    </NUpload>
    <div class="gallery-hint">
      最多 {{ MAX_GALLERY_IMAGES }} 张，第一张自动作为封面；单张原图不超过 5MB，超过 500KB 自动压缩。
    </div>
  </div>
</template>

<style scoped>
.gallery-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.gallery-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.gallery-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px;
  border: 1px solid #e8e8e4;
  border-radius: 10px;
  background: #fafaf8;
}

.gallery-preview {
  flex: none;
  overflow: hidden;
  border-radius: 8px;
}

.gallery-meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
}

.gallery-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #666762;
  font-weight: 600;
}

.gallery-actions {
  display: flex;
  gap: 8px;
}

.gallery-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 88px;
  border: 1px dashed #d9d9d3;
  border-radius: 10px;
  color: #9b9b96;
  background: #fafaf8;
}

.gallery-hint {
  color: #9b9b96;
  font-size: 12px;
}
</style>
