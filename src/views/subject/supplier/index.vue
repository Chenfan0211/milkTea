<script setup lang="ts">

defineOptions({
  name: 'subject_supplier'
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
    store.subjects.filter(s => s.type === 'supplier'),
    p.search,
    p.page,
    p.pageSize
  );

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
    { title: '可提现余额(元)', key: 'balance', width: 130, align: 'right', render: (row: any) => { const acc = store.subjectAccounts.find((a: any) => a.subjectId === row.id); return acc ? (acc.availableBalance ?? 0).toFixed(2) : '—'; } },
  { title: '关联商品数', key: 'productCount', width: 120, align: 'right' },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: renderTag('status', statusMap({ active: ['启用', 'success'], disabled: ['停用', 'default'] }))
  },
  { title: '创建时间', key: 'createTime', width: 150 }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '名称', placeholder: '名称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '启用', value: 'active' },
      { label: '停用', value: 'disabled' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '新增供应商', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
    { label: '余额明细', type: 'info', handler: (row: any) => router.push({ path: '/finance/flow', query: { subjectId: row.id } }) },
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该供应商？（请填写备注）',
    handler: (row, reason) =>
      store.patch('subjects', row.id, { status: 'disabled' }, '主体管理', '停用', 'name', reason),
    visible: row => row.status === 'active'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该供应商？（请填写备注）',
    handler: (row, reason) => store.patch('subjects', row.id, { status: 'active' }, '主体管理', '启用', 'name', reason),
    visible: row => row.status === 'disabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该供应商？（请填写备注）',
    handler: (row, reason) => store.remove('subjects', row.id, '主体管理', 'name', reason)
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' }
];

const config: AdminListConfig = {
  title: '供应商管理',
  remoteKey: 'subjects',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '供应商',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('subjects', editing.id, { ...data, type: 'supplier' }, '主体管理', 'name');
      else store.add('subjects', { ...data, type: 'supplier' }, '主体管理', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

