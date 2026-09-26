<script setup lang="ts">
defineOptions({
  name: 'marketing_gift'
});

import { h, ref } from 'vue';
import { NImage, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, RowAction, SearchField } from '@/views/_shared/types';
import { fetchGiftCardFaces, updateGiftCardFaceStatus } from '@/service/api/crud';
import type { GiftCardFace } from '@/service/api/crud';
import { formatFen } from '@/views/_shared/render';
import GiftCardEditor from './GiftCardEditor.vue';
import GiftGroupManager from './GiftGroupManager.vue';

const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);
const editorVisible = ref(false);
const editingFace = ref<GiftCardFace | null>(null);
const groupVisible = ref(false);

function reload() {
  listRef.value?.reload();
}

function openEditor(row?: GiftCardFace) {
  editingFace.value = row ?? null;
  editorVisible.value = true;
}

const columns: DataTableColumns<GiftCardFace> = [
  {
    title: '卡种分组',
    key: 'groupTitle',
    width: 140,
    render: row => row.groupTitle || '—'
  },
  {
    title: '卡面名称',
    key: 'cardName',
    minWidth: 160,
    render: row => row.cardName || '—'
  },
  {
    title: '卡面',
    key: 'cardImage',
    width: 140,
    render: row =>
      row.cardImage
        ? h(NImage, {
            src: row.cardImage,
            width: 90,
            height: 54,
            objectFit: 'cover',
            style: 'border-radius: 6px',
            previewDisabled: false
          })
        : '—'
  },
  {
    title: '面额',
    key: 'denominations',
    minWidth: 240,
    render: row => {
      const values = (row.denominations ?? [])
        .filter(item => !item.deleted)
        .map(item => `¥${formatFen(item.amount)}（售价 ¥${formatFen(item.salePrice)}）`);
      return values.join('；') || '—';
    }
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row => {
      const enabled = row.status === 'enabled';
      return h(
        NTag,
        { size: 'small', bordered: false, type: enabled ? 'success' : 'default' },
        { default: () => (enabled ? '已上架' : '已下架') }
      );
    }
  }
];

const searchFields: SearchField[] = [{ key: 'name', label: '卡面名称', placeholder: '请输入卡面名称' }];

const toolbar: RowAction[] = [
  { label: '新增礼品卡', type: 'primary', handler: () => openEditor() },
  { label: '分组管理', handler: () => (groupVisible.value = true) }
];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', handler: row => openEditor(row) },
  {
    label: '上架',
    type: 'success',
    confirm: '确认上架该礼品卡？',
    visible: row => row.status === 'disabled',
    handler: async row => {
      await updateGiftCardFaceStatus(Number(row.faceId), true);
      window.$message?.success('礼品卡已上架');
    }
  },
  {
    label: '下架',
    type: 'warning',
    confirm: '确认下架该礼品卡？',
    visible: row => row.status === 'enabled',
    handler: async row => {
      await updateGiftCardFaceStatus(Number(row.faceId), false);
      window.$message?.success('礼品卡已下架');
    }
  }
];

const config: AdminListConfig = {
  title: '礼品卡管理',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => {
    const result = await fetchGiftCardFaces({
      current: page,
      size: pageSize,
      name: String(search?.name || '').trim() || undefined
    });
    return {
      data: (result.records ?? []).map(item => ({ ...item, id: item.faceId })),
      total: result.total ?? 0
    };
  }
};
</script>

<template>
  <div class="page-root">
    <AdminListPage ref="listRef" :config="config" />
    <GiftCardEditor v-model:show="editorVisible" :face="editingFace" @saved="reload" />
    <GiftGroupManager v-model:show="groupVisible" @changed="reload" />
  </div>
</template>

<style scoped>
.page-root {
  padding: 0;
}
</style>