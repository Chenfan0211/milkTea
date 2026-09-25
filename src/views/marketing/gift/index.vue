<script setup lang="ts">
defineOptions({
  name: 'marketing_gift'
});

import { h, ref } from 'vue';
import { NImage } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { fetchGiftCardFaces } from '@/service/api/crud';

const columns: DataTableColumns<any> = [
  { title: '卡种分组', key: 'groupTitle', width: 140, render: (row: any) => row.groupTitle || '—' },
  { title: '卡面名称', key: 'cardName', minWidth: 160, render: (row: any) => row.cardName || '—' },
  {
    title: '卡面',
    key: 'cardImage',
    minWidth: 180,
    render: (row: any) =>
      h(NImage, {
        src: row.cardImage,
        width: 90,
        height: 54,
        objectFit: 'cover',
        style: 'border-radius: 6px',
        previewDisabled: false
      })
  },
  {
    title: '可选面额(元)',
    key: 'faceValues',
    minWidth: 180,
    render: (row: any) => (row.faceValues || []).map((v: number) => `¥${(v / 100).toFixed(0)}`).join(' / ') || '—'
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: (row: any) => (row.status === 'enabled' ? '启用' : '停用')
  }
];

const searchFields: SearchField[] = [{ key: 'name', label: '卡面', placeholder: '卡面名称' }];
const config: AdminListConfig = {
  title: '礼品卡',
  columns,
  searchFields,
  toolbar: [],
  rowActions: [],
  loadData: async ({ page, pageSize, search }) => {
    // 服务端分页：接口返回 PageResult，total 为「聚合后」卡面总数（非平铺面额行数）。
    // 分页必须作用在聚合结果上，否则同一卡面的多个面额会被拆到相邻两页。
    const res = await fetchGiftCardFaces({ current: page, size: pageSize, name: search?.name });
    return { data: res?.records ?? [], total: res?.total ?? 0 };
  }
};
</script>

<template>
  <div class="page-root">
    <AdminListPage :config="config" />
  </div>
</template>

<style scoped>
.page-root {
  padding: 0;
}
</style>
