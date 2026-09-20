<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig } from '@/views/_shared/types';
import type { SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();
const roleLabel = (v: string) =>
  (({ store: '门店', investor: '投资人', channel: '渠道' }) as Record<string, string>)[v] ?? v;
const columns: DataTableColumns<any> = [
  { title: '申请人', key: 'nickName', width: 140 },
  { title: '角色', key: 'roleType', width: 100, render: (row: any) => roleLabel(row.roleType) },
  { title: '金额(元)', key: 'amount', width: 110, align: 'right', render: renderMoney('amount') },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: renderTag(
      'status',
      statusMap({ pending: ['待审核', 'warning'], approved: ['已通过', 'success'], rejected: ['已驳回', 'error'] })
    )
  },
  { title: '申请时间', key: 'applyTime', width: 180 },
  { title: '审核人', key: 'reviewer', width: 110, render: (row: any) => row.reviewer || '—' }
];
const searchFields: SearchField[] = [
  { key: 'nickName', label: '申请人', placeholder: '昵称' },
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
    handler: row => store.reviewWithdraw(row.id, true),
    visible: row => row.status === 'pending'
  },
  {
    label: '驳回',
    type: 'error',
    handler: row => store.reviewWithdraw(row.id, false),
    visible: row => row.status === 'pending'
  }
];
const config: AdminListConfig = {
  title: '提现管理',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.withdrawals, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
