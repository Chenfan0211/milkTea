<script setup lang="ts">

defineOptions({
  name: 'auth_grant'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '用户', key: 'userId', width: 120 },
  { title: '角色', key: 'roleCode', width: 100 },
  { title: '主体', key: 'subjectId', minWidth: 160 },
  { title: '数据范围', key: 'dataScope', width: 110 },
  { title: '授权人', key: 'grantBy', width: 110 },
  { title: '授权时间', key: 'grantTime', render: renderDateTime('grantTime'), width: 150 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag('status', statusMap({ active: ['有效', 'success'], revoked: ['已撤销', 'default'] }))
  }
];
const searchFields: SearchField[] = [
  { key: 'subjectId', label: '主体', placeholder: '主体ID' },
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
    // 取值必须是库中 role_code 的真实值（大写英文），
    // 原实现用中文（门店/资源方...），与库中 STORE/CHANNEL... 不符，
    // 会造成「按角色筛选查不到、写入后展示不一致」。
    key: 'roleCode',
    label: '角色',
    type: 'select',
    options: [
      { label: '门店', value: 'STORE' },
      { label: '资源方', value: 'CHANNEL' },
      { label: '投资人', value: 'INVESTOR' },
      { label: '供应商', value: 'SUPPLIER' }
    ]
  },
  { key: 'subjectId', label: '主体ID' },
  { key: 'dataScope', label: '数据范围' }
];
const toolbar: RowAction[] = [{ label: '新增授权', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  {
    label: '撤销',
    type: 'error',
    reasonPrompt: '确认撤销该授权？（请填写备注）',
    handler: async (row, reason) => await store.patch('grants', row.id, { status: 'revoked' }, '授权中心', '撤销授权', 'subject', reason)
  }
];
const config: AdminListConfig = {
  title: '角色授权记录',
  remoteKey: 'grants',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('grants', search, page, pageSize),
  form: {
    title: '授权',
    fields: formFields,
    onSubmit: async (data, editing) => {
      if (editing)
        await store.update(
          'grants',
          editing.id,
          { ...data, status: 'active', grantBy: 'admin', grantTime: new Date().toISOString().slice(0, 16) },
          '授权中心',
          'subject'
        );
      else
        await store.add(
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

