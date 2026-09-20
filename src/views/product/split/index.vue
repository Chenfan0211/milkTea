<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const ratioCol = (key: string, title: string) => ({
  title,
  key,
  width: 90,
  align: 'right' as const,
  render: (row: any) => `${(row[key] / 100).toFixed(1)}%`
});
const columns: DataTableColumns<any> = [
  { title: '规则编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  { title: '作用范围', key: 'scope', width: 100 },
  ratioCol('platformRatio', '平台'),
  ratioCol('storeRatio', '门店'),
  ratioCol('channelRatio', '渠道'),
  ratioCol('investorRatio', '投资人'),
  ratioCol('supplierRatio', '供应商'),
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
  { key: 'platformRatio', label: '平台(万分比)', type: 'number' },
  { key: 'storeRatio', label: '门店(万分比)', type: 'number' },
  { key: 'channelRatio', label: '渠道(万分比)', type: 'number' },
  { key: 'investorRatio', label: '投资人(万分比)', type: 'number' },
  { key: 'supplierRatio', label: '供应商(万分比)', type: 'number' }
];
const toolbar: RowAction[] = [{ label: '新增分账规则', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  { label: '启用', type: 'success', handler: row => store.enableSplitRule(row.id) },
  {
    label: '停用',
    type: 'warning',
    handler: row => store.patch('splitRules', row.id, { status: 'disabled' }, '商品中心', '停用规则', 'name')
  },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该规则？',
    handler: row => store.remove('splitRules', row.id, '商品中心', 'name')
  }
];
const config: AdminListConfig = {
  title: '分账规则',
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
