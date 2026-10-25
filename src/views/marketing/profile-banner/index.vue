<script setup lang="ts">
defineOptions({
  name: 'MarketingProfileBanner'
});

import { h, ref } from 'vue';
import { NImage, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, RowAction, SearchField } from '@/views/_shared/types';
import { useAdminStore } from '@/store/modules/admin';
import ProfileBannerEditor from './ProfileBannerEditor.vue';

interface ProfileBanner {
  id: number;
  imageUrl: string;
  altText: string;
  sort: number;
  status: 'enabled' | 'disabled';
}

const store = useAdminStore();
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);
const editorVisible = ref(false);
const editingBanner = ref<ProfileBanner | null>(null);

function reload() {
  listRef.value?.reload();
}

function openEditor(banner?: ProfileBanner) {
  editingBanner.value = banner ?? null;
  editorVisible.value = true;
}

const columns: DataTableColumns<ProfileBanner> = [
  {
    title: '预览图',
    key: 'imageUrl',
    width: 180,
    render: row =>
      row.imageUrl
        ? h(NImage, {
            src: row.imageUrl,
            width: 160,
            height: 36,
            objectFit: 'cover',
            style: 'border-radius: 6px',
            previewDisabled: false
          })
        : '—'
  },
  { title: '图片说明', key: 'altText', minWidth: 180, render: row => row.altText || '—' },
  { title: '排序', key: 'sort', width: 90, align: 'right' },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row => {
      const enabled = row.status === 'enabled';
      return h(
        NTag,
        { size: 'small', bordered: false, type: enabled ? 'success' : 'default' },
        { default: () => (enabled ? '启用' : '停用') }
      );
    }
  }
];

const searchFields: SearchField[] = [{ key: 'altText', label: '图片说明', placeholder: '请输入图片说明' }];

const toolbar: RowAction[] = [{ label: '新增轮播图', type: 'primary', handler: () => openEditor() }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', handler: row => openEditor(row) },
  {
    label: '启用',
    type: 'success',
    confirm: '确认启用该轮播图？',
    visible: row => row.status === 'disabled',
    handler: async row => {
      await store.update('profileBanners', row.id, { status: 'enabled' }, '营销中心', 'altText');
      window.$message?.success('轮播图已启用');
    }
  },
  {
    label: '停用',
    type: 'warning',
    confirm: '确认停用该轮播图？',
    visible: row => row.status === 'enabled',
    handler: async row => {
      await store.update('profileBanners', row.id, { status: 'disabled' }, '营销中心', 'altText');
      window.$message?.success('轮播图已停用');
    }
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该轮播图？（请填写备注）',
    handler: async (row, reason) => await store.remove('profileBanners', row.id, '营销中心', 'altText', reason)
  }
];

const config: AdminListConfig = {
  title: '我的页轮播',
  remoteKey: 'profileBanners',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) =>
    store.queryRemote('profileBanners', { altText: String(search?.altText || '').trim() || undefined }, page, pageSize)
};
</script>

<template>
  <div class="page-root">
    <AdminListPage ref="listRef" :config="config" />
    <ProfileBannerEditor v-model:show="editorVisible" :banner="editingBanner" @saved="reload" />
  </div>
</template>

<style scoped>
.page-root {
  padding: 0;
}
</style>
