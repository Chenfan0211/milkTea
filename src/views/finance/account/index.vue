<script setup lang="ts">
defineOptions({
  name: 'FinanceAccount'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderMoney } from '@/views/_shared/render';
import { financeRoleLabel, financeRoleOptions } from '../role-label';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '经营方', key: 'subjectName', width: 150 },
  { title: '角色', key: 'roleType', width: 100, render: row => financeRoleLabel(row.roleType) },
  {
    title: '可提现余额(元)',
    key: 'availableBalance',
    width: 140,
    align: 'right',
    render: renderMoney('availableBalance')
  },
  { title: '冻结余额(元)', key: 'frozenBalance', width: 120, align: 'right', render: renderMoney('frozenBalance') },
  {
    title: '押金(元)',
    key: 'depositBalance',
    width: 120,
    align: 'right',
    render: renderMoney('depositBalance')
  },
  { title: '累计应得(元)', key: 'totalIncome', width: 130, align: 'right', render: renderMoney('totalIncome') },
  { title: '累计已提现(元)', key: 'totalWithdrawn', width: 140, align: 'right', render: renderMoney('totalWithdrawn') }
];

const searchFields: SearchField[] = [
  { key: 'subjectName', label: '经营方', placeholder: '经营方名称' },
  {
    key: 'roleType',
    label: '角色',
    type: 'select',
    options: financeRoleOptions
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
  },
  {
    label: '设置押金',
    type: 'info',
    // 押金是退款兜底金：提现后发生售后退款、可用余额不足时从这里抵扣。
    // 押金不可提现、不分账到子商户，钱始终留在平台侧。
    reasonPrompt: '请输入目标押金金额（元）。押金用于退款兜底，不可提现。',
    handler: (row, reason) => {
      const input = window.prompt('目标押金金额（元）', '0');
      if (input === null) return Promise.resolve();
      const yuan = Number(input);
      if (!Number.isFinite(yuan) || yuan < 0) {
        window.$message?.warning('请输入合法的押金金额');
        return Promise.resolve();
      }
      // 元 → 分，避免浮点误差
      return store.setDeposit(row.subjectId, Math.round(yuan * 100), reason);
    },
    visible: row => row.roleType !== 'platform'
  }
];

const config: AdminListConfig = {
  title: '经营方账户',
  remoteKey: 'subjectAccounts',
  columns,
  searchFields,
  toolbar: [],
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('subjectAccounts', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
