<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

const roleOptions = [
  { label: '门店', value: 'store' },
  { label: '投资人', value: 'investor' },
  { label: '渠道', value: 'channel' }
];

const roleLabel = (v: string | null) => {
  if (!v) return '未绑定';
  return roleOptions.find(o => o.value === v)?.label ?? v;
};

const columns: DataTableColumns<any> = [
  { title: '用户ID', key: 'userId', width: 120 },
  { title: '昵称', key: 'nickName', width: 140 },
  { title: 'openId', key: 'openId', minWidth: 160 },
  {
    title: '经营角色',
    key: 'businessRole',
    width: 120,
    render: (row: any) => roleLabel(row.businessRole)
  },
  { title: '绑定主体', key: 'boundSubjectName', minWidth: 140, render: (row: any) => row.boundSubjectName || '—' }
];

const searchFields: SearchField[] = [
  { key: 'nickName', label: '昵称', placeholder: '微信昵称' },
  { key: 'userId', label: '用户ID', placeholder: '用户ID' }
];

const formFields: FormField[] = [
  { key: 'userId', label: '用户ID', placeholder: '如 U1008' },
  { key: 'nickName', label: '昵称', placeholder: '微信昵称' },
  { key: 'openId', label: 'openId', placeholder: 'openid_xxx' }
];

const toolbar: RowAction[] = [{ label: '新增用户', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '绑定门店',
    type: 'info',
    picker: {
      title: '选择门店',
      options: () => store.subjects.filter(s => s.type === 'store').map(s => ({ label: s.name, value: s.code }))
    },
    handler: (row, picked) => {
      if (!picked) return;
      const subj = store.subjects.find(s => s.code === picked);
      if (subj) store.bindUserRole(row.id, 'store', subj.code, subj.name);
    }
  },
  {
    label: '绑定投资人',
    type: 'info',
    picker: {
      title: '选择投资人',
      options: () => store.subjects.filter(s => s.type === 'investor').map(s => ({ label: s.name, value: s.code }))
    },
    handler: (row, picked) => {
      if (!picked) return;
      const subj = store.subjects.find(s => s.code === picked);
      if (subj) store.bindUserRole(row.id, 'investor', subj.code, subj.name);
    }
  },
  {
    label: '绑定渠道',
    type: 'info',
    picker: {
      title: '选择渠道',
      options: () => store.subjects.filter(s => s.type === 'channel').map(s => ({ label: s.name, value: s.code }))
    },
    handler: (row, picked) => {
      if (!picked) return;
      const subj = store.subjects.find(s => s.code === picked);
      if (subj) store.bindUserRole(row.id, 'channel', subj.code, subj.name);
    }
  },
  { label: '解绑', type: 'error', confirm: '确认解绑该用户的经营角色？', handler: row => store.unbindUserRole(row.id) },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该用户？',
    handler: row => store.remove('users', row.id, '用户管理', 'nickName')
  }
];

const config: AdminListConfig = {
  title: '用户列表',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.users, search, page, pageSize),
  form: {
    title: '用户',
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
