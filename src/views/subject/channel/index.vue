<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const load = async (p: any) =>
  store.listFiltered(
    store.subjects.filter(s => s.type === 'channel'),
    p.search,
    p.page,
    p.pageSize
  );

const columns: DataTableColumns<any> = [
  { title: '编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  { title: '渠道码', key: 'channelCode', width: 130 },
  { title: '绑定用户数', key: 'boundUserCount', width: 110, align: 'right' },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: renderTag('status', statusMap({ active: ['启用', 'success'], disabled: ['停用', 'default'] }))
  },
  { title: '创建时间', key: 'createTime', width: 180 }
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
const toolbar: RowAction[] = [{ label: '新增渠道', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '启/停用',
    type: 'warning',
    handler: row =>
      store.patch(
        'subjects',
        row.id,
        { status: row.status === 'active' ? 'disabled' : 'active' },
        '主体管理',
        '启停用',
        'name'
      )
  },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该渠道？',
    handler: row => store.remove('subjects', row.id, '主体管理', 'name')
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  { key: 'channelCode', label: '渠道码' }
];

const config: AdminListConfig = {
  title: '渠道管理',
  columns,
  searchFields,
  loadData: load,
  toolbar,
  rowActions,
  form: {
    title: '渠道',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('subjects', editing.id, { ...data, type: 'channel' }, '主体管理', 'name');
      else store.add('subjects', { ...data, type: 'channel' }, '主体管理', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
