<script setup lang="ts">

defineOptions({
  name: 'subject_platform'
});

import { useRouter } from 'vue-router';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();
const router = useRouter();

const load = async (p: any) =>
  store.listFiltered(
    store.subjects.filter(s => s.type === 'platform'),
    p.search,
    p.page,
    p.pageSize
  );

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  { title: '小程序AppID', key: 'appid', width: 150, render: (row: any) => row.appid || '—' },
  { title: '名称', key: 'name', minWidth: 160 },
  { title: '可提现余额(元)', key: 'balance', width: 130, align: 'right', render: (row: any) => { const acc = store.subjectAccounts.find((a: any) => a.subjectId === row.id); return acc ? (acc.availableBalance ?? 0).toFixed(2) : '—'; } },
  {
    title: '账号状态',
    key: 'status',
    width: 110,
    render: renderTag('status', statusMap({ active: ['正常', 'success'], pending: ['待签约', 'warning'] }))
  },
  { title: '创建时间', key: 'createTime', width: 150 }
];

const searchFields: SearchField[] = [{ key: 'name', label: '名称', placeholder: '名称' }];
const toolbar: RowAction[] = [{ label: '新增平台主体', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
    { label: '余额明细', type: 'info', handler: (row: any) => router.push({ path: '/finance/flow', query: { subjectId: row.id } }) },
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该主体？（请填写备注）',
    handler: (row, reason) => store.remove('subjects', row.id, '主体管理', 'name', reason)
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  { key: 'appid', label: '小程序AppID' },
  { key: 'appSecret', label: 'AppSecret' },
  { key: 'withdrawFreeAuditThreshold', label: '提现免审阈值(元)', type: 'number' },
  {
    key: 'status',
    label: '账号状态',
    type: 'select',
    options: [
      { label: '正常', value: 'active' },
      { label: '待签约', value: 'pending' }
    ]
  }
];

const config: AdminListConfig = {
  title: '平台主体',
  remoteKey: 'subjects',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '平台主体',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('subjects', editing.id, { ...data, type: 'platform' }, '主体管理', 'name');
      else store.add('subjects', { ...data, type: 'platform' }, '主体管理', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

