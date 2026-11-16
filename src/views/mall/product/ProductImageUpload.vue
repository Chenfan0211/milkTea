<script setup lang="ts">
defineOptions({
  name: 'ProductImageUpload'
});

import { ref } from 'vue';
import { NButton, NImage, NUpload } from 'naive-ui';
import type { UploadCustomRequestOptions } from 'naive-ui';
import { uploadAdminImage } from '@/service/api/crud';

const props = withDefaults(
  defineProps<{
    modelValue: string;
    size?: number;
    disabled?: boolean;
    label?: string;
  }>(),
  {
    size: 104,
    disabled: false,
    label: '商品图片'
  }
);

const emit = defineEmits<{
  'update:modelValue': [value: string];
}>();

const uploading = ref(false);
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

function validateUpload(file: File) {
  const name = String(file.name || '').toLowerCase();
  const validType = ['image/jpeg', 'image/png'].includes(file.type);
  const validExt = name.endsWith('.jpg') || name.endsWith('.jpeg') || name.endsWith('.png');
  if (!validType && !validExt) return '仅支持 JPG 或 PNG 图片';
  if (file.size > MAX_UPLOAD_BYTES) return '图片不能超过 5MB';
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
    emit('update:modelValue', result.url);
    options.onFinish();
    window.$message?.success('图片上传成功');
  } catch (error: any) {
    options.onError();
    window.$message?.error(error?.message || '图片上传失败');
  } finally {
    uploading.value = false;
  }
}

function removeImage() {
  emit('update:modelValue', '');
}
</script>

<template>
  <div class="product-image-upload">
    <NUpload
      accept=".jpg,.jpeg,.png,image/jpeg,image/png"
      :show-file-list="false"
      :custom-request="handleUpload"
      :disabled="disabled || uploading"
    >
      <NImage
        v-if="props.modelValue"
        :src="props.modelValue"
        :width="props.size"
        :height="props.size"
        object-fit="cover"
        class="image-preview"
      />
      <div v-else class="image-empty" :style="{ width: `${props.size}px`, height: `${props.size}px` }">
        <span>点击上传</span>
        <small>{{ props.label }}</small>
      </div>
    </NUpload>
    <div class="image-actions">
      <NButton size="tiny" :loading="uploading" :disabled="disabled">
        {{ props.modelValue ? '重新上传' : '上传图片' }}
      </NButton>
      <NButton v-if="props.modelValue" size="tiny" quaternary :disabled="disabled || uploading" @click="removeImage">
        移除
      </NButton>
    </div>
    <div class="image-hint">JPG/PNG，最大 5MB</div>
  </div>
</template>

<style scoped>
.product-image-upload {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.image-preview {
  overflow: hidden;
  border: 1px solid #e8e8e4;
  border-radius: 10px;
}

.image-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 4px;
  border: 1px dashed #d9d9d3;
  border-radius: 10px;
  color: #666762;
  background: #fafaf8;
}

.image-empty small,
.image-hint {
  color: #9b9b96;
  font-size: 12px;
}

.image-actions {
  display: flex;
  gap: 4px;
}
</style>
