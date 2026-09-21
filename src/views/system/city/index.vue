<script setup lang="ts">

defineOptions({
  name: 'system_city'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const provinceLabel = (code: string) => store.provinces.find((p: any) => p.code === code)?.name ?? code;

const columns: DataTableColumns<any> = [
  { title: '省份', key: 'provinceCode', width: 120, render: (row: any) => provinceLabel(row.provinceCode) },
  { title: '城市', key: 'name', width: 140 },
  { title: '城市编码', key: 'code', width: 140 },
  { title: '经度', key: 'longitude', width: 110, align: 'right' },
  { title: '纬度', key: 'latitude', width: 110, align: 'right' },
  { title: '排序', key: 'sort', width: 80, align: 'right' },
  {
    title: '状态',
    key: 'enabled',
    width: 100,
    render: (row: any) => renderTag('enabled', statusMap({ true: ['启用', 'success'], false: ['停用', 'default'] }))(row)
  }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '城市', placeholder: '城市名称' },
  {
    key: 'provinceCode',
    label: '省份',
    type: 'select',
    options: () => store.provinces.map((p: any) => ({ label: p.name, value: p.code }))
  }
];

const formFields: FormField[] = [
  {
    key: 'provinceCode',
    label: '省份',
    type: 'select',
    options: () => store.provinces.map((p: any) => ({ label: p.name, value: p.code }))
  },
  { key: 'name', label: '城市名称' },
  { key: 'code', label: '城市编码' },
  { key: 'longitude', label: '经度', type: 'number' },
  { key: 'latitude', label: '纬度', type: 'number' },
  { key: 'sort', label: '排序', type: 'number' }
];

const toolbar: RowAction[] = [{ label: '新增城市', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该城市？（请填写备注）',
    handler: row => store.update('cities', row.id, { enabled: false }, '数据字典', 'name'),
    visible: row => row.enabled !== false
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该城市？（请填写备注）',
    handler: row => store.update('cities', row.id, { enabled: true }, '数据字典', 'name'),
    visible: row => row.enabled === false
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该城市？（请填写备注）',
    handler: (row, reason) => store.remove('cities', row.id, '数据字典', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '城市管理',
  remoteKey: 'cities',
  remoteDeps: ['provinces'],
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.cities, search, page, pageSize),
  form: {
    title: '城市',
    fields: formFields,
    onSubmit: (data, editing) => {
      const payload = { ...data, enabled: true };
      if (editing) store.update('cities', editing.id, payload, '数据字典', 'name');
      else store.add('cities', payload, '数据字典', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

