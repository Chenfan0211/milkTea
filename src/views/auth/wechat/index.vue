<script setup lang="ts">

defineOptions({
  name: 'auth_wechat'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: 'openId', key: 'openId', minWidth: 160 },
  { title: '微信昵称', key: 'nickName', width: 140 },
  { title: '绑定用户', key: 'userId', width: 120 },
  {
    title: '经营角色',
    key: 'businessRole',
    width: 120,
    render: (row: any) =>
      row.businessRole
        ? ({ store: '门店', investor: '投资人', resource: '资源方' } as Record<string, string>)[row.businessRole]
        : '未绑定'
  },
  { title: '绑定状态', key: 'businessRole', width: 110, render: (row: any) => (row.businessRole ? '已绑定' : '未绑定') }
];
const searchFields: SearchField[] = [
  { key: 'nickName', label: '昵称', placeholder: '微信昵称' },
  { key: 'openId', label: 'openId', placeholder: 'openId' }
];
const toolbar: RowAction[] = [{ label: '绑定微信账号', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  {
    label: '查看角色',
    type: 'info',
    handler: row => window.$message?.info(row.businessRole ? `角色：${row.businessRole}` : '未绑定角色')
  },
  {
    label: '解绑',
    type: 'error',
    reasonPrompt: '解绑后旧角色会话立即失效，确认解绑？（请填写备注）',
    handler: (row, reason) => store.unbindUserRole(row.id, reason)
  }
];
const formFields: FormField[] = [
  { key: 'userId', label: '用户ID' },
  { key: 'nickName', label: '昵称' },
  { key: 'openId', label: 'openId' }
];
const config: AdminListConfig = {
  title: '微信账号绑定',
  remoteKey: 'users',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.users, search, page, pageSize),
  form: {
    title: '微信账号',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('users', editing.id, data, '用户管理', 'nickName');
      else store.add('users', data, '用户管理', 'nickName');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

