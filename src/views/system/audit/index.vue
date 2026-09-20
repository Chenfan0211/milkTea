<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '时间', key: 'time', width: 180 },
  { title: '操作人', key: 'operator', width: 110 },
  { title: '模块', key: 'module', width: 100 },
  { title: '操作类型', key: 'action', width: 100 },
  { title: '对象', key: 'target', width: 140 },
  { title: '前后值', key: 'beforeValue', width: 120, render: (row: any) => `${row.beforeValue} → ${row.afterValue}` },
  { title: 'IP', key: 'ip', width: 130 }
];
const searchFields: SearchField[] = [
  { key: 'operator', label: '操作人', placeholder: '操作人' },
  { key: 'module', label: '模块', placeholder: '模块' }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  { label: '详情', type: 'info', handler: row => window.$message?.info(`${row.action}：${row.target}`) }
];
const config: AdminListConfig = {
  title: '审计日志',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.auditLogs, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
