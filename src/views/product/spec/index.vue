<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '规格组', key: 'group', width: 120 },
  { title: '名称', key: 'name', width: 180 },
  { title: '可选值', key: 'options', minWidth: 240, render: (row: any) => (row.options || []).join(' / ') },
  { title: '排序', key: 'order', width: 90, align: 'right' }
];
const searchFields: SearchField[] = [{ key: 'name', label: '规格', placeholder: '规格名称' }];
const formFields: FormField[] = [
  { key: 'group', label: '规格组' },
  { key: 'name', label: '名称' },
  { key: 'order', label: '排序', type: 'number' }
];
const toolbar: RowAction[] = [{ label: '新增规格', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该规格？',
    handler: row => store.remove('specs', row.id, '商品中心', 'name')
  }
];
const config: AdminListConfig = {
  title: '规格管理',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.specs, search, page, pageSize),
  form: {
    title: '规格',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('specs', editing.id, data, '商品中心', 'name');
      else store.add('specs', { ...data, options: [] }, '商品中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
