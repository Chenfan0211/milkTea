<script setup lang="ts">

defineOptions({
  name: 'trade_refund'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney, renderDateTime } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '退款单号', key: 'refundNo', width: 140 },
  { title: '原订单', key: 'orderNo', width: 160 },
  { title: '金额(元)', key: 'amount', width: 110, align: 'right', render: renderMoney('amount') },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: renderTag(
      'status',
      statusMap({
        SUCCESS: ['退款成功', 'success'],
        REJECTED: ['已驳回', 'error']
      })
    )
  },
  { title: '申请时间', key: 'applyTime', render: renderDateTime('applyTime'), width: 150 }
];
const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '原订单号' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '退款成功', value: 'SUCCESS' },
      { label: '已驳回', value: 'REJECTED' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [];
const config: AdminListConfig = {
  title: '退款管理',
  remoteKey: 'refunds',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('refunds', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

