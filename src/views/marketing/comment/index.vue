<script setup lang="ts">

defineOptions({
  name: 'marketing_comment'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';

const store = useAdminStore();
const columns: DataTableColumns<any> = [
  { title: '用户', key: 'nickName', width: 130 },
  { title: '商品', key: 'product', width: 140 },
  { title: '评分', key: 'rating', width: 80, align: 'right' },
  { title: '内容', key: 'content', minWidth: 200 },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: renderTag(
      'status',
      statusMap({ PENDING: ['待审核', 'warning'], APPROVED: ['已通过', 'success'], REJECTED: ['已驳回', 'error'] })
    )
  },
  { title: '时间', key: 'time', render: renderDateTime('time'), width: 150 }
];
const searchFields: SearchField[] = [
  { key: 'product', label: '商品', placeholder: '商品名称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待审核', value: 'PENDING' },
      { label: '已通过', value: 'APPROVED' },
      { label: '已驳回', value: 'REJECTED' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [];
const config: AdminListConfig = {
  title: '评论审核',
  remoteKey: 'comments',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('comments', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
