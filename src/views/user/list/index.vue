<script setup lang="ts">

defineOptions({
  name: 'user_list'
});

import { h } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { NAvatar } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import type { RoleType } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const roleOptions = [
  { label: '门店', value: 'store' },
  { label: '投资人', value: 'investor' },
  { label: '资源方', value: 'resource' }
];

const roleLabel = (v: string | null) => {
  if (!v) return '未绑定';
  return roleOptions.find(o => o.value === v)?.label ?? v;
};

const genderMap = statusMap({
  male: ['男', 'info'],
  female: ['女', 'success'],
  unknown: ['未知', 'default']
});

const columns: DataTableColumns<any> = [
  {
    title: '头像',
    key: 'avatar',
    width: 80,
    render: (row: any) =>
      row.avatar
        ? h(NAvatar, { round: true, size: 40, src: row.avatar, style: 'display:block' })
        : h('span', { style: 'color:#9B9B96' }, '—')
  },
  { title: '用户ID', key: 'userId', width: 110 },
  { title: '昵称', key: 'nickName', width: 140 },
  {
    title: '性别',
    key: 'gender',
    width: 80,
    render: renderTag('gender', genderMap)
  },
  { title: '生日', key: 'birthday', width: 120, render: (row: any) => row.birthday || '—' },
  {
    title: '等级',
    key: 'vipLevel',
    width: 90,
    render: (row: any) => row.vipLevel || '—'
  },
  { title: '时光币', key: 'points', width: 90, align: 'right', render: (row: any) => String(row.points ?? 0) },
  { title: '储值金额', key: 'balance', width: 88, align: 'right', render: (row: any) => `¥${row.balance ?? 0}` },
  {
    title: '经营角色',
    key: 'businessRole',
    width: 95,
    render: (row: any) => roleLabel(row.businessRole)
  },
  { title: '绑定主体', key: 'boundSubjectName', minWidth: 140, render: (row: any) => row.boundSubjectName || '—' }
];

const searchFields: SearchField[] = [
  { key: 'nickName', label: '昵称', placeholder: '微信昵称' },
  { key: 'userId', label: '用户ID', placeholder: '用户ID' }
];

const rowActions: RowAction[] = [
  {
    label: '绑定',
    type: 'info',
    cascadePicker: {
      title: '绑定主体',
      steps: [
        {
          label: '经营者类型',
          options: () => [
            { label: '门店', value: 'store' },
            { label: '投资人', value: 'investor' },
            { label: '资源方', value: 'resource' }
          ]
        },
        {
          label: '具体主体',
          options: (type) =>
            store.subjects
              .filter(s => s.type === type && !s.boundUserId && !s.deleted)
              .map(s => ({ label: s.name, value: s.code }))
        }
      ],
      handler: (row, values) => {
        const subj = store.subjects.find(x => x.code === values.second);
        if (subj) store.bindUserRole(row.id, values.first as RoleType, subj.code, subj.name);
      }
    }
  },
  {
    label: '解绑',
    type: 'error',
    reasonPrompt: '确认解绑该用户的经营角色？（请填写备注）',
    handler: (row, reason) => store.unbindUserRole(row.id, reason)
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该用户？删除后列表不再展示（逻辑删除），请填写备注',
    handler: (row, reason) => store.remove('users', row.id, '用户管理', 'nickName', reason)
  }
];

const config: AdminListConfig = {
  title: '用户列表',
  remoteKey: 'users',
  columns,
  searchFields,
  toolbar: [],
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.users, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
