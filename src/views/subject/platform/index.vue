<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const load = async (p: any) =>
  store.listFiltered(
    store.subjects.filter(s => s.type === 'platform'),
    p.search,
    p.page,
    p.pageSize
  );

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  {
    title: '账号状态',
    key: 'status',
    width: 110,
    render: renderTag('status', statusMap({ active: ['正常', 'success'], pending: ['待签约', 'warning'] }))
  },
  { title: '创建时间', key: 'createTime', width: 180 }
];

const searchFields: SearchField[] = [{ key: 'name', label: '名称', placeholder: '名称' }];
const toolbar: RowAction[] = [{ label: '新增平台主体', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该主体？',
    handler: row => store.remove('subjects', row.id, '主体管理', 'name')
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
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
