<script setup lang="ts">
defineOptions({ name: 'MallBanner' });

import { h, ref } from 'vue';
import { NImage, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, RowAction, SearchField } from '@/views/_shared/types';
import { useAdminStore } from '@/store/modules/admin';
import { useAdminImageUrl } from '@/views/_shared/use-admin-image-url';
import MallBannerEditor from './MallBannerEditor.vue';

interface MallBanner {
  id: number;
  imageUrl: string;
  altText: string;
  sort: number;
  status: 'enabled' | 'disabled';
  targetType: string;
  targetId: string;
}

const store = useAdminStore();
const adminImageUrl = useAdminImageUrl();
const bannerFallback = '/assets/images/3x/mall-category-placeholder.jpg';
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);
const editorVisible = ref(false);
const editingBanner = ref<MallBanner | null>(null);
const targetLabels: Record<string, string> = {
  none: '不跳转',
  mallProduct: '商城商品'
};

function openEditor(banner?: MallBanner) {
  editingBanner.value = banner ?? null;
  editorVisible.value = true;
}

const columns: DataTableColumns<MallBanner> = [
  {
    title: '预览图',
    key: 'imageUrl',
    width: 150,
    render: row =>
      row.imageUrl
        ? h(NImage, {
            src: adminImageUrl(row.imageUrl),
            fallbackSrc: bannerFallback,
            width: 120,
            height: 44,
            objectFit: 'cover',
            previewDisabled: false
          })
        : '—'
  },
  { title: '图片说明', key: 'altText', minWidth: 180, render: row => row.altText || '—' },
  {
    title: '跳转目标',
    key: 'targetType',
    minWidth: 150,
    render: row =>
      row.targetId
        ? `${targetLabels[row.targetType] || '商城商品'}：${row.targetId}`
        : targetLabels[row.targetType] || '不跳转'
  },
  { title: '排序', key: 'sort', width: 90, align: 'right' },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.status === 'enabled' ? 'success' : 'default' },
        { default: () => (row.status === 'enabled' ? '启用' : '停用') }
      )
  }
];

const searchFields: SearchField[] = [{ key: 'altText', label: '图片说明', placeholder: '请输入图片说明' }];
const toolbar: RowAction[] = [{ label: '新增轮播图', type: 'primary', handler: () => openEditor() }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', handler: row => openEditor(row) },
  {
    label: '启用',
    type: 'success',
    confirm: '确认启用该商城轮播图？',
    visible: row => row.status === 'disabled',
    handler: async row => {
      await store.update('mallBanners', row.id, { status: 'enabled' }, '商城轮播', 'altText');
      window.$message?.success('轮播图已启用');
    }
  },
  {
    label: '停用',
    type: 'warning',
    confirm: '确认停用该商城轮播图？',
    visible: row => row.status === 'enabled',
    handler: async row => {
      await store.update('mallBanners', row.id, { status: 'disabled' }, '商城轮播', 'altText');
      window.$message?.success('轮播图已停用');
    }
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该商城轮播图？（请填写备注）',
    handler: async (row, reason) => store.remove('mallBanners', row.id, '商城轮播', 'altText', reason)
  }
];

const config: AdminListConfig = {
  title: '商城轮播',
  remoteKey: 'mallBanners',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) =>
    store.queryRemote('mallBanners', { altText: String(search?.altText || '').trim() || undefined }, page, pageSize)
};
</script>

<template>
  <div class="page-root">
    <p class="page-tip">商城轮播固定展示 710×260rpx；全部停用或删除后，小程序隐藏轮播区域。</p>
    <AdminListPage ref="listRef" :config="config" />
    <MallBannerEditor v-model:show="editorVisible" :banner="editingBanner" @saved="listRef?.reload()" />
  </div>
</template>

<style scoped>
.page-root {
  padding: 0;
}
.page-tip {
  margin: 0 0 16px;
  color: #777873;
}
</style>
