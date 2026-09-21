<script setup lang="ts">

defineOptions({
  name: 'product_category'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 140 },
  { title: '名称', key: 'name', width: 140 },
  { title: '分类标签', key: 'tag', width: 120, render: (row: any) => row.tag || '—' },
  { title: '排序', key: 'sort', width: 80, align: 'right' },
  {
    title: '状态',
    key: 'enabled',
    width: 100,
    render: (row: any) => renderTag('enabled', statusMap({ true: ['启用', 'success'], false: ['停用', 'default'] }))(row)
  }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '分类名称', placeholder: '分类名称' },
  {
    key: 'enabled',
    label: '状态',
    type: 'select',
    options: [
      { label: '启用', value: 'true' },
      { label: '停用', value: 'false' }
    ]
  }
];

const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  { key: 'tag', label: '分类标签' },
  { key: 'sort', label: '排序', type: 'number' }
];

const toolbar: RowAction[] = [{ label: '新增分类', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该分类？（请填写备注）',
    handler: row => store.update('productCategories', row.id, { enabled: false }, '商品中心', 'name'),
    visible: row => row.enabled !== false
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该分类？（请填写备注）',
    handler: row => store.update('productCategories', row.id, { enabled: true }, '商品中心', 'name'),
    visible: row => row.enabled === false
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该分类？（请填写备注）',
    handler: (row, reason) => store.remove('productCategories', row.id, '商品中心', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '分类管理',
  remoteKey: 'productCategories',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.productCategories, search, page, pageSize),
  form: {
    title: '分类',
    fields: formFields,
    onSubmit: (data, editing) => {
      const payload = { ...data, enabled: true };
      if (editing) store.update('productCategories', editing.id, payload, '商品中心', 'name');
      else store.add('productCategories', payload, '商品中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

