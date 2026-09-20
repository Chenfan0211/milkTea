<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

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
        PENDING: ['待审核', 'info'],
        APPROVED: ['已通过', 'primary'],
        SUCCESS: ['退款成功', 'success'],
        REJECTED: ['已驳回', 'error']
      })
    )
  },
  { title: '申请时间', key: 'applyTime', width: 180 }
];
const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '原订单号' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待审核', value: 'PENDING' },
      { label: '已通过', value: 'APPROVED' },
      { label: '退款成功', value: 'SUCCESS' },
      { label: '已驳回', value: 'REJECTED' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  {
    label: '审核通过',
    type: 'success',
    handler: row => store.refundAudit(row.id, true),
    visible: row => row.status === 'PENDING'
  },
  {
    label: '驳回',
    type: 'error',
    handler: row => store.refundAudit(row.id, false),
    visible: row => row.status === 'PENDING'
  }
];
const config: AdminListConfig = {
  title: '退款管理',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.refunds, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
