<script setup lang="ts">

defineOptions({
  name: 'finance_reconcile'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney, renderDateTime } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  {
    title: '异常类型',
    key: 'issueType',
    width: 120,
    render: renderTag(
      'issueType',
      statusMap({ MISSING_SPLIT: ['缺失分账快照', 'info'], MISSING_REVERSE: ['退款未冲正', 'warning'], SPLIT_AMOUNT_MISMATCH: ['分账金额不一致', 'error'] })
    )
  },
  { title: '订单号', key: 'orderNo', width: 160 },
  { title: '系统值', key: 'systemValue', width: 110 },
  { title: '三方值', key: 'thirdValue', width: 110 },
  { title: '差异金额(元)', key: 'diffAmount', width: 120, align: 'right' as const, render: renderMoney('diffAmount') },
  { title: '发现时间', key: 'foundTime', width: 170, render: renderDateTime('foundTime') },
  {
    title: '处理状态',
    key: 'status',
    width: 110,
    render: renderTag('status', statusMap({ OPEN: ['待处理', 'warning'], RESOLVED: ['已处理', 'success'], IGNORED: ['已忽略', 'default'] }))
  }
];
const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待处理', value: 'OPEN' },
      { label: '已处理', value: 'RESOLVED' }
    ]
  }
];
const toolbar: RowAction[] = [
  {
    label: '重新对账',
    type: 'primary',
    reasonPrompt: '确认触发重新对账？（请填写备注）',
    handler: async () => window.$message?.success('已触发重新对账')
  }
];
const rowActions: RowAction[] = [
  {
    label: '标记处理',
    type: 'success',
    reasonPrompt: '确认标记处理该异常？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('reconciles', row.id, { status: 'RESOLVED' }, '财务中心', '标记处理', 'orderNo', reason)
  }
];
const config: AdminListConfig = {
  title: '对账异常池',
  remoteKey: 'reconciles',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('reconciles', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

