<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();
const columns: DataTableColumns<any> = [
  { title: '等级', key: 'level', width: 90 },
  { title: '名称', key: 'name', width: 130 },
  { title: '门槛(元)', key: 'amountTarget', width: 110, align: 'right' },
  { title: '折扣', key: 'discount', width: 90 },
  { title: '权益', key: 'benefits', minWidth: 240, render: (row: any) => (row.benefits || []).join('、') }
];
const searchFields: SearchField[] = [{ key: 'name', label: '名称', placeholder: '等级名称' }];
const formFields: FormField[] = [
  { key: 'level', label: '等级' },
  { key: 'name', label: '名称' },
  { key: 'amountTarget', label: '门槛(元)', type: 'number' },
  { key: 'discount', label: '折扣' }
];
const toolbar: RowAction[] = [{ label: '新增等级', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该等级？',
    handler: row => store.remove('memberLevels', row.id, '营销中心', 'name')
  }
];
const config: AdminListConfig = {
  title: '会员等级',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.memberLevels, search, page, pageSize),
  form: {
    title: '会员等级',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('memberLevels', editing.id, data, '营销中心', 'name');
      else store.add('memberLevels', { ...data, benefits: [] }, '营销中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
