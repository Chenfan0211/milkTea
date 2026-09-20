<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const load = async (p: any) =>
  store.listFiltered(
    store.subjects.filter(s => s.type === 'investor'),
    p.search,
    p.page,
    p.pageSize
  );

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  { title: '可投门店数', key: 'investableStoreCount', width: 110, align: 'right' },
  { title: '关联门店', key: 'relatedStore', width: 130 },
  {
    title: '签约状态',
    key: 'signStatus',
    width: 110,
    render: renderTag('signStatus', statusMap({ signed: ['已签约', 'success'], pending: ['审核中', 'warning'] }))
  },
  { title: '创建时间', key: 'createTime', width: 180 }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '名称', placeholder: '名称' },
  {
    key: 'signStatus',
    label: '签约状态',
    type: 'select',
    options: [
      { label: '已签约', value: 'signed' },
      { label: '审核中', value: 'pending' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '新增投资人', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '签约审批',
    type: 'info',
    handler: row => store.patch('subjects', row.id, { signStatus: 'signed' }, '主体管理', '签约审批', 'name')
  },
  {
    label: '绑定门店',
    type: 'info',
    handler: row => {
      const st = store.subjects.find(s => s.type === 'store');
      if (st) store.patch('subjects', row.id, { relatedStore: st.name }, '主体管理', '绑定门店', 'name');
    }
  },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该投资人？',
    handler: row => store.remove('subjects', row.id, '主体管理', 'name')
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '姓名' }
];

const config: AdminListConfig = {
  title: '投资人管理',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '投资人',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('subjects', editing.id, { ...data, type: 'investor' }, '主体管理', 'name');
      else store.add('subjects', { ...data, type: 'investor' }, '主体管理', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
