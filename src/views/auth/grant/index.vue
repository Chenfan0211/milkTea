<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '用户', key: 'userId', width: 120 },
  { title: '角色', key: 'role', width: 100 },
  { title: '主体', key: 'subject', minWidth: 160 },
  { title: '数据范围', key: 'dataScope', width: 110 },
  { title: '授权人', key: 'grantBy', width: 110 },
  { title: '授权时间', key: 'grantTime', width: 180 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag('status', statusMap({ active: ['有效', 'success'], revoked: ['已撤销', 'default'] }))
  }
];
const searchFields: SearchField[] = [
  { key: 'subject', label: '主体', placeholder: '主体名称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '有效', value: 'active' },
      { label: '已撤销', value: 'revoked' }
    ]
  }
];
const formFields: FormField[] = [
  { key: 'userId', label: '用户ID' },
  {
    key: 'role',
    label: '角色',
    type: 'select',
    options: [
      { label: '门店', value: '门店' },
      { label: '渠道', value: '渠道' },
      { label: '投资人', value: '投资人' },
      { label: '供应商', value: '供应商' }
    ]
  },
  { key: 'subject', label: '主体' },
  { key: 'dataScope', label: '数据范围' }
];
const toolbar: RowAction[] = [{ label: '新增授权', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  {
    label: '撤销',
    type: 'error',
    confirm: '确认撤销该授权？',
    handler: row => store.patch('grants', row.id, { status: 'revoked' }, '授权中心', '撤销授权', 'subject')
  }
];
const config: AdminListConfig = {
  title: '角色授权记录',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.grants, search, page, pageSize),
  form: {
    title: '授权',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing)
        store.update(
          'grants',
          editing.id,
          { ...data, status: 'active', grantBy: 'admin', grantTime: new Date().toISOString().slice(0, 16) },
          '授权中心',
          'subject'
        );
      else
        store.add(
          'grants',
          { ...data, status: 'active', grantBy: 'admin', grantTime: new Date().toISOString().slice(0, 16) },
          '授权中心',
          'subject'
        );
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
