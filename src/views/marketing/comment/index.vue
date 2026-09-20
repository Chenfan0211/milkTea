<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

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
      statusMap({ pending: ['待审核', 'warning'], approved: ['已通过', 'success'], rejected: ['已驳回', 'error'] })
    )
  },
  { title: '时间', key: 'time', width: 180 }
];
const searchFields: SearchField[] = [
  { key: 'product', label: '商品', placeholder: '商品名称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待审核', value: 'pending' },
      { label: '已通过', value: 'approved' },
      { label: '已驳回', value: 'rejected' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  {
    label: '通过',
    type: 'success',
    handler: row => store.reviewComment(row.id, true),
    visible: row => row.status === 'pending'
  },
  {
    label: '驳回',
    type: 'error',
    handler: row => store.reviewComment(row.id, false),
    visible: row => row.status === 'pending'
  }
];
const config: AdminListConfig = {
  title: '评论审核',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.comments, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
