<script setup lang="ts">

defineOptions({
  name: 'auth_role'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderDateTime } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '角色编码', key: 'code', width: 140 },
  { title: '名称', key: 'name', width: 140 },
  { title: '数据范围', key: 'dataScope', width: 120 },
  { title: '创建时间', key: 'createTime', render: renderDateTime('createTime'), width: 150 }
];
const searchFields: SearchField[] = [
  { key: 'name', label: '角色', placeholder: '角色名称' },
  {
    key: 'dataScope',
    label: '数据范围',
    type: 'select',
    options: [
      { label: '平台级', value: '平台级' },
      { label: '门店级', value: '门店级' }
    ]
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  {
    key: 'dataScope',
    label: '数据范围',
    type: 'select',
    options: [
      { label: '平台级', value: '平台级' },
      { label: '门店级', value: '门店级' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '新增角色', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该角色？（请填写备注）',
    handler: async (row, reason) => await store.remove('roles', row.id, '授权中心', 'name', reason)
  }
];
const config: AdminListConfig = {
  title: '角色与权限',
  remoteKey: 'roles',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('roles', search, page, pageSize),
  form: {
    title: '角色',
    fields: formFields,
    onSubmit: async (data, editing) => {
      if (editing) await store.update('roles', editing.id, data, '授权中心', 'name');
      else await store.add('roles', data, '授权中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

