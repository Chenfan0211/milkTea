<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig } from '@/views/_shared/types';
import type { SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderMoney } from '@/views/_shared/render';

const store = useAdminStore();
const columns: DataTableColumns<any> = [
  { title: '取餐码', key: 'pickupCode', width: 120 },
  { title: '订单号', key: 'orderNo', width: 200 },
  { title: '商品', key: 'product', minWidth: 180 },
  { title: '规格', key: 'spec', minWidth: 160 },
  { title: '金额(元)', key: 'amount', width: 110, align: 'right', render: renderMoney('amount') }
];
const searchFields: SearchField[] = [
  { key: 'pickupCode', label: '取餐码', placeholder: '取餐码' },
  { key: 'orderNo', label: '订单号', placeholder: '订单号' }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  { label: '执行核销', type: 'success', confirm: '确认核销该订单？', handler: row => store.executeVerify(row.id) }
];
const config: AdminListConfig = {
  title: '待核销池',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.verifyPool, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
