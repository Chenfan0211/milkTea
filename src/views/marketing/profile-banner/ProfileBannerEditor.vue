<script setup lang="ts">
defineOptions({
  name: 'ProfileBannerEditor'
});

import { computed, reactive, watch } from 'vue';
import { NButton, NForm, NFormItem, NImage, NInput, NInputNumber, NModal, NSelect, NUpload } from 'naive-ui';
import type { UploadCustomRequestOptions } from 'naive-ui';
import { uploadAdminImage } from '@/service/api/crud';
import { useAdminStore } from '@/store/modules/admin';
import { ref } from 'vue';
import BannerTargetFields from '@/views/_shared/BannerTargetFields.vue';
import { compressBannerImage, MAX_BANNER_BYTES } from '@/views/_shared/banner-image';
import { useAdminImageUrl } from '@/views/_shared/use-admin-image-url';

interface ProfileBanner {
  id?: number;
  imageUrl: string;
  altText: string;
  sort: number;
  status: 'enabled' | 'disabled';
  targetType: string;
  targetId: string;
}

const props = defineProps<{
  show: boolean;
  banner: ProfileBanner | null;
}>();

const emit = defineEmits<{
  'update:show': [boolean];
  saved: [];
}>();

const store = useAdminStore();
const submitting = ref(false);
const uploading = ref(false);
const adminImageUrl = useAdminImageUrl();
const bannerFallback = '/assets/images/3x/profile-banner.jpg';
const form = reactive<ProfileBanner>({
  imageUrl: '',
  altText: '',
  sort: 0,
  status: 'enabled',
  targetType: 'menu',
  targetId: ''
});

const title = computed(() => (props.banner?.id ? '编辑轮播图' : '新增轮播图'));
const previewUrl = computed(() => adminImageUrl(form.imageUrl));
const statusOptions = [
  { label: '启用', value: 'enabled' },
  { label: '停用', value: 'disabled' }
];

watch(
  () => [props.show, props.banner] as const,
  () => {
    if (!props.show) return;
    const source = props.banner;
    form.id = source?.id;
    form.imageUrl = source?.imageUrl || '';
    form.altText = source?.altText || '';
    form.sort = Number(source?.sort ?? 0);
    form.status = source?.status === 'disabled' ? 'disabled' : 'enabled';
    form.targetType = source?.targetType || 'menu';
    form.targetId = source?.targetId || '';
  },
  { immediate: true }
);

async function handleUpload(options: UploadCustomRequestOptions) {
  const file = options.file.file;
  if (!(file instanceof File)) {
    options.onError();
    return;
  }
  uploading.value = true;
  try {
    const compressed = await compressBannerImage(file, { maxWidth: 1420, maxHeight: 320 });
    const result = await uploadAdminImage(compressed);
    if (result.size <= 0 || result.size > MAX_BANNER_BYTES) throw new Error('服务端保存的图片超过 500KB，请换一张图片');
    form.imageUrl = result.url;
    options.onFinish();
    window.$message?.success('图片上传成功');
  } catch (error: any) {
    options.onError();
    window.$message?.error(error?.message || '图片上传失败');
  } finally {
    uploading.value = false;
  }
}

async function submit() {
  if (!form.imageUrl) {
    window.$message?.warning('请先上传轮播图片');
    return;
  }
  if (['menuProduct', 'mallProduct'].includes(form.targetType) && !form.targetId) {
    window.$message?.warning('请选择目标商品');
    return;
  }
  const payload = {
    imageUrl: form.imageUrl,
    altText: form.altText.trim() || '我的页活动图',
    sort: Number(form.sort ?? 0),
    status: form.status,
    targetType: form.targetType,
    targetId: form.targetId
  };
  submitting.value = true;
  try {
    if (form.id) {
      await store.update('profileBanners', form.id, payload, '营销中心', 'altText');
      window.$message?.success('轮播图已保存');
    } else {
      await store.add('profileBanners', payload, '营销中心', 'altText');
      window.$message?.success('轮播图已新增');
    }
    emit('saved');
    emit('update:show', false);
  } catch (error: any) {
    window.$message?.error(error?.message || '轮播图保存失败');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <NModal :show="show" preset="card" :title="title" class="w-680px" @update:show="emit('update:show', $event)">
    <NForm label-placement="left" :label-width="96">
      <NFormItem label="轮播图片" required>
        <div class="image-field">
          <NImage
            v-if="form.imageUrl"
            :src="previewUrl"
            :fallback-src="bannerFallback"
            width="240"
            height="54"
            object-fit="cover"
            class="image-preview"
          />
          <div v-else class="image-empty">暂无图片</div>
          <NUpload accept=".jpg,.jpeg,.png,image/jpeg,image/png" :show-file-list="false" :custom-request="handleUpload">
            <NButton :loading="uploading">{{ form.imageUrl ? '重新上传' : '上传图片' }}</NButton>
          </NUpload>
        </div>
        <div class="field-tip">
          推荐尺寸 2130×480，固定 710×160rpx 居中裁切。JPG/PNG 原图不超过 5MB，上传时自动压缩至 500KB 内。
        </div>
      </NFormItem>

      <NFormItem label="图片说明">
        <NInput v-model:value="form.altText" maxlength="40" show-count placeholder="例如：抹茶轻茉莉新品" />
      </NFormItem>

      <BannerTargetFields v-model:target-type="form.targetType" v-model:target-id="form.targetId" />

      <NFormItem label="排序">
        <NInputNumber v-model:value="form.sort" :min="0" :precision="0" class="w-160px" />
      </NFormItem>

      <NFormItem label="状态">
        <NSelect v-model:value="form.status" :options="statusOptions" class="w-160px" />
      </NFormItem>
    </NForm>

    <div class="modal-actions">
      <NButton @click="emit('update:show', false)">取消</NButton>
      <NButton type="primary" :loading="submitting" :disabled="uploading" @click="submit">确定</NButton>
    </div>
  </NModal>
</template>

<style scoped>
.image-field {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px;
}

.image-preview {
  overflow: hidden;
  border-radius: 8px;
}

.image-empty {
  display: flex;
  width: 240px;
  height: 54px;
  align-items: center;
  justify-content: center;
  border: 1px dashed #d9d9d3;
  border-radius: 8px;
  color: #9b9b96;
  background: #fafaf8;
}

.field-tip {
  width: 100%;
  margin-top: 8px;
  color: #9b9b96;
  font-size: 12px;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}
</style>
