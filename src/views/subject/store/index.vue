<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const load = async (p: any) =>
  store.listFiltered(
    store.subjects.filter(s => s.type === 'store'),
    p.search,
    p.page,
    p.pageSize
  );

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  { title: '城市', key: 'city', width: 100 },
  {
    title: '营业状态',
    key: 'status',
    width: 110,
    render: renderTag('status', statusMap({ open: ['营业中', 'success'], closed: ['停业', 'default'] }))
  },
  { title: '负责人', key: 'manager', width: 110 },
  { title: '关联投资人', key: 'investorName', width: 130 },
  { title: '创建时间', key: 'createTime', width: 180 }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '名称', placeholder: '名称' },
  {
    key: 'status',
    label: '营业状态',
    type: 'select',
    options: [
      { label: '营业中', value: 'open' },
      { label: '停业', value: 'closed' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '新增门店', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停业/营业',
    type: 'warning',
    handler: row =>
      store.patch(
        'subjects',
        row.id,
        { status: row.status === 'open' ? 'closed' : 'open' },
        '主体管理',
        '切换营业状态',
        'name'
      )
  },
  {
    label: '绑定投资人',
    type: 'info',
    handler: row => {
      const inv = store.subjects.find(s => s.type === 'investor');
      if (inv) store.patch('subjects', row.id, { investorName: inv.name }, '主体管理', '绑定投资人', 'name');
    }
  },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该门店？',
    handler: row => store.remove('subjects', row.id, '主体管理', 'name')
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  { key: 'city', label: '城市' },
  { key: 'manager', label: '负责人' },
  {
    key: 'status',
    label: '营业状态',
    type: 'select',
    options: [
      { label: '营业中', value: 'open' },
      { label: '停业', value: 'closed' }
    ]
  }
];

const config: AdminListConfig = {
  title: '门店管理',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '门店',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('subjects', editing.id, { ...data, type: 'store' }, '主体管理', 'name');
      else store.add('subjects', { ...data, type: 'store' }, '主体管理', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
