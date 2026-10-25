<script setup lang="ts">
defineOptions({
  name: 'SystemHomeDecor'
});

import { computed, onMounted, ref } from 'vue';
import { NAlert, NButton, NCard, NForm, NFormItem, NImage, NInput, NSpin, NUpload } from 'naive-ui';
import type { UploadCustomRequestOptions } from 'naive-ui';
import { uploadAdminImage } from '@/service/api/crud';
import { useAdminStore } from '@/store/modules/admin';

const DEFAULT_HERO_URL = '/assets/images/3x/home-hero.jpg';
const DEFAULT_HERO_ALT = '金秋入茶限定上新';
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;

const store = useAdminStore();
const loading = ref(true);
const saving = ref(false);
const uploading = ref(false);
const rowId = ref<number | null>(null);
const imageUrl = ref('');
const altText = ref(DEFAULT_HERO_ALT);

const previewUrl = computed(() => imageUrl.value || DEFAULT_HERO_URL);

function parseConfig(row: any) {
  const raw = row?.value;
  if (raw && typeof raw === 'object') return raw;
  if (typeof raw !== 'string' || !raw.trim()) return {};
  try {
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

async function loadConfig() {
  loading.value = true;
  try {
    const result = await store.queryRemote('appConfig', { configKey: 'home_hero' }, 1, 20);
    const row = (result.data || []).find((item: any) => item.configKey === 'home_hero');
    if (!row) {
      rowId.value = null;
      imageUrl.value = '';
      altText.value = DEFAULT_HERO_ALT;
      return;
    }
    const config = parseConfig(row);
    rowId.value = Number(row.id);
    imageUrl.value = typeof config.imageUrl === 'string' ? config.imageUrl.trim() : '';
    altText.value =
      typeof config.altText === 'string' && config.altText.trim() ? config.altText.trim() : DEFAULT_HERO_ALT;
  } catch (error: any) {
    window.$message?.error(error?.message || '首页装修配置加载失败');
  } finally {
    loading.value = false;
  }
}

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
    imageUrl.value = result.url;
    options.onFinish();
    window.$message?.success('图片上传成功，请保存后生效');
  } catch (error: any) {
    options.onError();
    window.$message?.error(error?.message || '图片上传失败');
  } finally {
    uploading.value = false;
  }
}

function buildValue() {
  return {
    imageUrl: imageUrl.value.trim(),
    altText: altText.value.trim() || DEFAULT_HERO_ALT,
    updatedAt: new Date().toISOString()
  };
}

async function save() {
  saving.value = true;
  try {
    const value = JSON.stringify(buildValue());
    if (rowId.value) {
      await store.update('appConfig', rowId.value, { value }, '首页装修', 'configName');
    } else {
      const created = await store.add(
        'appConfig',
        {
          configKey: 'home_hero',
          configName: '首页主视觉背景图',
          value,
          sort: 9,
          status: 'enabled',
          remark: '首页主视觉背景图；请通过“首页装修”页面维护'
        },
        '首页装修',
        'configName'
      );
      rowId.value = Number(created?.id) || null;
    }
    window.$message?.success('首页主视觉已保存');
  } catch (error: any) {
    window.$message?.error(error?.message || '首页主视觉保存失败');
  } finally {
    saving.value = false;
  }
}

async function restoreDefault() {
  imageUrl.value = '';
  altText.value = DEFAULT_HERO_ALT;
  await save();
}

onMounted(loadConfig);
</script>

<template>
  <NSpin :show="loading">
    <NCard title="首页装修" :bordered="false">
      <NAlert type="info" :show-icon="false" class="decor-tip">
        小程序首页主视觉固定展示为 750×1040rpx。建议上传 2250×3120 的 JPG/PNG，单张不超过
        5MB；窗口比例不一致时会居中裁切。
      </NAlert>

      <div class="decor-layout">
        <div class="phone-preview">
          <div class="phone-preview__screen">
            <NImage :src="previewUrl" object-fit="cover" class="phone-preview__image" />
          </div>
          <div class="phone-preview__caption">首页主视觉预览（固定裁切）</div>
        </div>

        <NForm label-placement="top" class="decor-form">
          <NFormItem label="背景图片">
            <div class="upload-block">
              <NUpload
                accept=".jpg,.jpeg,.png,image/jpeg,image/png"
                :show-file-list="false"
                :custom-request="handleUpload"
              >
                <NButton type="primary" :loading="uploading">{{ imageUrl ? '重新上传' : '上传图片' }}</NButton>
              </NUpload>
              <NButton v-if="imageUrl" secondary :disabled="saving || uploading" @click="restoreDefault">
                恢复默认图
              </NButton>
            </div>
          </NFormItem>

          <NFormItem label="图片说明">
            <NInput v-model:value="altText" maxlength="40" show-count placeholder="例如：金秋入茶限定上新" />
          </NFormItem>

          <div class="decor-actions">
            <NButton type="primary" :loading="saving" :disabled="uploading" @click="save">保存并生效</NButton>
          </div>
        </NForm>
      </div>
    </NCard>
  </NSpin>
</template>

<style scoped>
.decor-tip {
  margin-bottom: 24px;
}

.decor-layout {
  display: flex;
  gap: 48px;
  align-items: flex-start;
}

.phone-preview {
  flex: 0 0 320px;
}

.phone-preview__screen {
  width: 320px;
  aspect-ratio: 750 / 1040;
  overflow: hidden;
  background: #f4f5f2;
  border: 1px solid #e5e7eb;
  border-radius: 18px;
  box-shadow: 0 12px 30px rgb(0 0 0 / 8%);
}

.phone-preview__image {
  width: 100%;
  height: 100%;
}

.phone-preview__caption {
  margin-top: 12px;
  color: #777873;
  font-size: 13px;
  text-align: center;
}

.decor-form {
  flex: 1;
  min-width: 360px;
  max-width: 560px;
}

.upload-block {
  display: flex;
  gap: 12px;
  align-items: center;
}

.decor-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 960px) {
  .decor-layout {
    flex-direction: column;
    align-items: stretch;
  }

  .phone-preview,
  .decor-form {
    width: 100%;
    min-width: 0;
    max-width: none;
  }
}
</style>
