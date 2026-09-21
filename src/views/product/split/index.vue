<script setup lang="ts">

defineOptions({
  name: 'product_split'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '规则编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  { title: '作用范围', key: 'scope', width: 100 },
  { title: '门店/件(元)', key: 'storePerItem', width: 120, align: 'right' },
  { title: '资源方/件(元)', key: 'channelPerItem', width: 120, align: 'right' },
  { title: '投资人比例', key: 'investorPercent', width: 110, align: 'right', render: (row: any) => `${row.investorPercent ?? 0}%` },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag('status', statusMap({ enabled: ['启用', 'success'], disabled: ['停用', 'default'] }))
  }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '规则', placeholder: '规则名称' },
  {
    key: 'scope',
    label: '范围',
    type: 'select',
    options: [
      { label: '全局', value: '全局' },
      { label: '商品', value: '商品' }
    ]
  }
];

const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  {
    key: 'scope',
    label: '范围',
    type: 'select',
    options: [
      { label: '全局', value: '全局' },
      { label: '商品', value: '商品' }
    ]
  },
  { key: 'storePerItem', label: '门店/件(元)', type: 'number' },
  { key: 'channelPerItem', label: '资源方/件(元)', type: 'number' },
  { key: 'investorPercent', label: '投资人比例(%)', type: 'number' }
];

const toolbar: RowAction[] = [{ label: '新增分账规则', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该规则？（请填写备注）',
    handler: (row, reason) => store.enableSplitRule(row.id, reason),
    visible: row => row.status === 'disabled'
  },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该规则？（请填写备注）',
    handler: (row, reason) =>
      store.patch('splitRules', row.id, { status: 'disabled' }, '商品中心', '停用规则', 'name', reason),
    visible: row => row.status === 'enabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该规则？（请填写备注）',
    handler: (row, reason) => store.remove('splitRules', row.id, '商品中心', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '分账规则',
  remoteKey: 'splitRules',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.splitRules, search, page, pageSize),
  form: {
    title: '分账规则',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('splitRules', editing.id, data, '商品中心', 'name');
      else store.add('splitRules', { ...data, status: 'disabled' }, '商品中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

