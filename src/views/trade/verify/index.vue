<script setup lang="ts">

defineOptions({
  name: 'trade_verify'
});

import { useRouter } from 'vue-router';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();
const router = useRouter();

const columns: DataTableColumns<any> = [
  { title: '核销码', key: 'verifyCode', width: 120 },
  { title: '订单号', key: 'orderNo', width: 160 },
  { title: '门店', key: 'store', width: 130 },
  { title: '操作人', key: 'operator', width: 110 },
  { title: '设备', key: 'device', width: 110 },
  {
    title: '核销类型',
    key: 'type',
    width: 88,
    render: renderTag('type', statusMap({ 订单: ['订单', 'success'], 兑换: ['兑换', 'warning'] }))
  },
  {
    title: '结果',
    key: 'result',
    width: 95,
    render: renderTag('result', statusMap({ success: ['核销成功', 'success'], rejected: ['重复拦截', 'error'] }))
  },
  { title: '时间', key: 'time', width: 150 }
];
const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  {
    key: 'type',
    label: '核销类型',
    type: 'select',
    options: [
      { label: '订单', value: '订单' },
      { label: '兑换', value: '兑换' }
    ]
  },
  {
    key: 'result',
    label: '结果',
    type: 'select',
    options: [
      { label: '核销成功', value: 'success' },
      { label: '重复拦截', value: 'rejected' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  { label: '详情', type: 'info', handler: row => router.push({ path: '/trade/verify-detail', query: { id: row.id } }) }
];
const config: AdminListConfig = {
  title: '核销记录',
  remoteKey: 'verifies',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.verifies, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

