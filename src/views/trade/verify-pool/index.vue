<script setup lang="ts">

defineOptions({
  name: 'trade_verify-pool'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig } from '@/views/_shared/types';
import type { SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderMoney, renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();
const verifyTypeMap = statusMap({ order: ['订单', 'success'], exchange: ['兑换', 'warning'] });
const columns: DataTableColumns<any> = [
  { title: '取餐码', key: 'pickupCode', width: 120 },
  { title: '类型', key: 'type', width: 90, render: renderTag('type', verifyTypeMap) },
  { title: '订单号', key: 'orderNo', width: 150 },
  { title: '商品', key: 'product', minWidth: 180 },
  { title: '规格', key: 'spec', minWidth: 140 },
  {
    title: '金额',
    key: 'amount',
    width: 120,
    align: 'right',
    render: row => (row.type === 'exchange' ? `${row.points || 0} 时光币` : renderMoney('amount')(row))
  }
];
const searchFields: SearchField[] = [
  { key: 'pickupCode', label: '取餐码', placeholder: '取餐码' },
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  {
    key: 'type',
    label: '类型',
    type: 'select',
    options: [
      { label: '订单', value: 'order' },
      { label: '兑换', value: 'exchange' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  {
    label: '执行核销',
    type: 'success',
    reasonPrompt: '确认核销该订单？（请填写备注）',
    handler: (row, reason) => store.executeVerify(row.id, reason)
  }
];
const config: AdminListConfig = {
  title: '待核销池',
  remoteKey: 'verifyPool',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('verifyPool', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

