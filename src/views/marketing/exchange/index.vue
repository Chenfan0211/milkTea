<script setup lang="ts">

defineOptions({
  name: 'marketing_exchange'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';

const store = useAdminStore();

const statusMapDef = statusMap({
  pending_payment: ['待支付', 'warning'],
  pending_verify: ['待核销', 'info'],
  completed: ['已完成', 'success']
});

const columns: DataTableColumns<any> = [
  { title: '兑换单号', key: 'recordNo', width: 145 },
  { title: '用户', key: 'user', width: 120 },
  { title: '商品', key: 'product', minWidth: 180 },
  { title: '时光币', key: 'points', width: 90, align: 'right' },
  { title: '状态', key: 'status', width: 100, render: renderTag('status', statusMapDef) },
  { title: '申请时间', key: 'applyTime', render: renderDateTime('applyTime'), width: 145 }
];

const searchFields: SearchField[] = [
  { key: 'user', label: '用户', placeholder: '用户昵称' },
  { key: 'product', label: '商品', placeholder: '商品名称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待支付', value: 'pending_payment' },
      { label: '待核销', value: 'pending_verify' },
      { label: '已完成', value: 'completed' }
    ]
  }
];

const rowActions: RowAction[] = [
  {
    label: '门店核销',
    type: 'success',
    reasonPrompt: '确认门店核销该兑换？（请填写备注）',
    handler: async (row, reason) => {
      await store.patch('exchangeRecords', row.id, { status: 'completed' }, '营销中心', '兑换核销', 'recordNo', reason);
    },
    visible: row => row.status === 'pending_verify'
  }
];

const config: AdminListConfig = {
  title: '兑换记录',
  remoteKey: 'exchangeRecords',
  columns,
  searchFields,
  toolbar: [],
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('exchangeRecords', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
