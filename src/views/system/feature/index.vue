<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { useRouteStore } from '@/store/modules/route';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();
const routeStore = useRouteStore();

const columns: DataTableColumns<any> = [
  { title: '开关编码', key: 'code', width: 180 },
  { title: '名称', key: 'name', width: 120 },
  {
    title: '默认状态',
    key: 'defaultStatus',
    width: 110,
    render: renderTag('defaultStatus', statusMap({ 关闭: ['关闭', 'default'], 开启: ['开启', 'success'] }))
  },
  {
    title: '当前状态',
    key: 'currentStatus',
    width: 110,
    render: renderTag('currentStatus', statusMap({ 关闭: ['关闭', 'default'], 开启: ['开启', 'success'] }))
  },
  { title: '开放条件', key: 'openCondition', minWidth: 260 }
];
const searchFields: SearchField[] = [{ key: 'name', label: '名称', placeholder: '功能名称' }];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  {
    label: '开启',
    type: 'success',
    confirm: '确认开启该功能开关？',
    handler: row => {
      store.toggleFeature(row.id, true);
      routeStore.refreshGlobalMenus();
    }
  },
  {
    label: '关闭',
    type: 'error',
    confirm: '确认关闭该功能开关？',
    handler: row => {
      store.toggleFeature(row.id, false);
      routeStore.refreshGlobalMenus();
    }
  }
];
const config: AdminListConfig = {
  title: '功能开关',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.features, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
