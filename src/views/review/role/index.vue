<script setup lang="ts">

defineOptions({
  name: 'review_role'
});

import { useRouter } from 'vue-router';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();
const router = useRouter();

const roleLabel = (v: string) => ({ store: '门店', investor: '投资人', resource: '资源方' })[v] ?? v;

const columns: DataTableColumns<any> = [
  { title: '申请人', key: 'nickName', width: 140 },
  { title: '用户ID', key: 'userId', width: 120 },
  { title: '姓名', key: 'name', width: 100 },
  { title: '手机号', key: 'phone', width: 130 },
  { title: '申请角色', key: 'roleType', width: 120, render: (row: any) => roleLabel(row.roleType) },
  {
    title: '状态',
    key: 'status',
    width: 95,
    render: renderTag(
      'status',
      statusMap({ pending: ['待审核', 'warning'], approved: ['已通过', 'success'], rejected: ['已驳回', 'error'] })
    )
  },
  { title: '申请时间', key: 'applyTime', width: 150 },
  { title: '审核人', key: 'reviewer', width: 95, render: (row: any) => row.reviewer || '—' }
];

const searchFields: SearchField[] = [
  { key: 'nickName', label: '申请人', placeholder: '昵称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待审核', value: 'pending' },
      { label: '已通过', value: 'approved' },
      { label: '已驳回', value: 'rejected' }
    ]
  }
];

const toolbar: RowAction[] = [];

const rowActions: RowAction[] = [
  { label: '详情', type: 'info', handler: row => router.push({ path: '/review/role-detail', query: { id: row.id } }) },
  {
    label: '通过',
    type: 'success',
    reasonPrompt: '确认通过该申请？（请填写备注）',
    handler: (row, reason) => store.reviewApplication(row.id, true, row.subjectId, row.subjectName, reason),
    visible: row => row.status === 'pending'
  },
  {
    label: '驳回',
    type: 'error',
    reasonPrompt: '确认驳回该申请？（请填写备注）',
    handler: (row, reason) => store.reviewApplication(row.id, false, null, null, reason),
    visible: row => row.status === 'pending'
  }
];

const config: AdminListConfig = {
  title: '角色开通审核',
  remoteKey: 'roleApplications',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.roleApplications, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

