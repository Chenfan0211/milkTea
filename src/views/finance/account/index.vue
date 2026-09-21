<script setup lang="ts">

defineOptions({
  name: 'finance_account'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderMoney } from '@/views/_shared/render';

const store = useAdminStore();

const roleLabel = (v: string) =>
  (({ platform: '平台', store: '门店', resource: '资源方', investor: '投资人', supplier: '供应商' }) as Record<string, string>)[v] ?? v;

const columns: DataTableColumns<any> = [
  { title: '经营方', key: 'subjectName', width: 150 },
  { title: '角色', key: 'roleType', width: 100, render: (row: any) => roleLabel(row.roleType) },
  { title: '可提现余额(元)', key: 'availableBalance', width: 140, align: 'right', render: renderMoney('availableBalance') },
  { title: '冻结余额(元)', key: 'frozenBalance', width: 120, align: 'right', render: renderMoney('frozenBalance') },
  { title: '累计应得(元)', key: 'totalIncome', width: 130, align: 'right', render: renderMoney('totalIncome') },
  { title: '累计已提现(元)', key: 'totalWithdrawn', width: 140, align: 'right', render: renderMoney('totalWithdrawn') }
];

const searchFields: SearchField[] = [
  { key: 'subjectName', label: '经营方', placeholder: '经营方名称' },
  {
    key: 'roleType',
    label: '角色',
    type: 'select',
    options: [
      { label: '平台', value: 'platform' },
      { label: '门店', value: 'store' },
      { label: '资源方', value: 'resource' },
      { label: '投资人', value: 'investor' },
      { label: '供应商', value: 'supplier' }
    ]
  }
];

const rowActions: RowAction[] = [
  {
    label: '冻结',
    type: 'warning',
    reasonPrompt: '确认冻结该经营方全部可提现余额？（请填写备注）',
    handler: (row, reason) => store.freezeAccount(row.subjectId, reason),
    visible: row => row.availableBalance > 0 && row.roleType !== 'platform'
  },
  {
    label: '解冻',
    type: 'success',
    reasonPrompt: '确认解冻该经营方冻结余额？（请填写备注）',
    handler: (row, reason) => store.unfreezeAccount(row.subjectId, reason),
    visible: row => row.frozenBalance > 0 && row.roleType !== 'platform'
  }
];

const config: AdminListConfig = {
  title: '经营方账户',
  remoteKey: 'subjectAccounts',
  columns,
  searchFields,
  toolbar: [],
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.subjectAccounts, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

