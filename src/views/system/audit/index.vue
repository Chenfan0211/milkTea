<script setup lang="ts">

defineOptions({
  name: 'system_audit'
});

import { computed } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

const moduleOptions = computed(() => {
  const set = new Set<string>();
  for (const row of store.auditLogs) {
    if (row.module) set.add(row.module);
  }
  return Array.from(set).map(m => ({ label: m, value: m }));
});

const columns: DataTableColumns<any> = [
  { title: '时间', key: 'time', width: 160 },
  { title: '操作人', key: 'operator', width: 100 },
  { title: '模块', key: 'module', width: 110 },
  { title: '操作类型', key: 'action', width: 110 },
  { title: '对象', key: 'target', width: 140, ellipsis: { tooltip: true } },
  {
    title: '旧值',
    key: 'beforeValue',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row: any) => row.beforeValue || '—'
  },
  {
    title: '新值',
    key: 'afterValue',
    minWidth: 200,
    ellipsis: { tooltip: true },
    render: (row: any) => row.afterValue || '—'
  }
];

const searchFields: SearchField[] = [
  { key: 'operator', label: '操作人', placeholder: '操作人' },
  { key: 'module', label: '模块', type: 'select', options: () => moduleOptions.value, placeholder: '请选择模块' }
];

const config: AdminListConfig = {
  title: '审计日志',
  remoteKey: 'auditLogs',
  columns,
  searchFields,
  toolbar: [],
  rowActions: [],
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.auditLogs, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

