<script setup lang="ts">

defineOptions({
  name: 'finance_snapshot'
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
  { title: '快照号', key: 'snapshotNo', width: 140 },
  { title: '订单号', key: 'orderNo', width: 150 },
  { title: '商品信息', key: 'summary', minWidth: 130, render: (row: any) => row.summary || '—' },
  { title: '供应商', key: 'supplierAmount', width: 90, align: 'right', render: (row: any) => `¥${(row.supplierAmount ?? 0).toFixed(2)}` },
  { title: '门店', key: 'storeAmount', width: 90, align: 'right', render: (row: any) => `¥${(row.storeAmount ?? 0).toFixed(2)}` },
  { title: '资源方', key: 'channelAmount', width: 90, align: 'right', render: (row: any) => `¥${(row.channelAmount ?? 0).toFixed(2)}` },
  { title: '投资人', key: 'investorAmount', width: 90, align: 'right', render: (row: any) => `¥${(row.investorAmount ?? 0).toFixed(2)}` },
  { title: '平台分佣', key: 'platformCommission', width: 88, align: 'right', render: (row: any) => `¥${(row.platformCommission ?? 0).toFixed(2)}` },
  { title: '平台提成', key: 'platformBonus', width: 88, align: 'right', render: (row: any) => `¥${(row.platformBonus ?? 0).toFixed(2)}` },
  { title: '平台合计', key: 'platformAmount', width: 88, align: 'right', render: (row: any) => `¥${(row.platformAmount ?? 0).toFixed(2)}` },
  {
    title: '合计校验',
    key: 'totalCheck',
    width: 88,
    render: renderTag('totalCheck', statusMap({ 一致: ['一致', 'success'], 不一致: ['不一致', 'error'] }))
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: renderTag('status', statusMap({ valid: ['有效', 'success'], invalid: ['已作废', 'default'] }))
  },
  { title: '创建时间', key: 'createTime', width: 145 }
];
const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '有效', value: 'valid' },
      { label: '已作废', value: 'invalid' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  {
    label: '详情',
    type: 'info',
    handler: row => router.push({ path: '/finance/snapshot-detail', query: { id: row.id } })
  },

];
const config: AdminListConfig = {
  title: '分账快照',
  remoteKey: 'snapshots',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.snapshots, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

