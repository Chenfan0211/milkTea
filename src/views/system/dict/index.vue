<script setup lang="ts">

defineOptions({
  name: 'system_dict'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const groupOptions = () =>
  Array.from(new Set(store.dictEntries.map((e: any) => e.groupName).filter(Boolean))).map(name => ({
    label: name,
    value: name
  }));

const columns: DataTableColumns<any> = [
  { title: '分组', key: 'groupName', width: 130 },
  { title: '编码', key: 'code', width: 140 },
  { title: '名称', key: 'name', width: 140 },
  { title: '排序', key: 'sort', width: 80, align: 'right' },
  {
    title: '状态',
    key: 'enabled',
    width: 100,
    render: (row: any) => renderTag('enabled', statusMap({ true: ['启用', 'success'], false: ['停用', 'default'] }))(row)
  }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '名称', placeholder: '名称' },
  { key: 'groupName', label: '分组', type: 'select', options: groupOptions }
];

const formFields: FormField[] = [
  { key: 'groupName', label: '分组', type: 'select', options: groupOptions },
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  { key: 'sort', label: '排序', type: 'number' }
];

const toolbar: RowAction[] = [{ label: '新增字典项', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该字典项？（请填写备注）',
    handler: row => store.update('dictEntries', row.id, { enabled: false }, '数据字典', 'name'),
    visible: row => row.enabled !== false
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该字典项？（请填写备注）',
    handler: row => store.update('dictEntries', row.id, { enabled: true }, '数据字典', 'name'),
    visible: row => row.enabled === false
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该字典项？（请填写备注）',
    handler: (row, reason) => store.remove('dictEntries', row.id, '数据字典', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '数据字典',
  remoteKey: 'dictEntries',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.dictEntries, search, page, pageSize),
  form: {
    title: '字典项',
    fields: formFields,
    onSubmit: (data, editing) => {
      const payload = { ...data, enabled: true };
      if (editing) store.update('dictEntries', editing.id, payload, '数据字典', 'name');
      else store.add('dictEntries', payload, '数据字典', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

