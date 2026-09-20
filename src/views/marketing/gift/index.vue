<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();
const columns: DataTableColumns<any> = [
  { title: '卡种名称', key: 'name', minWidth: 180 },
  { title: '图片', key: 'image', minWidth: 220 }
];
const searchFields: SearchField[] = [{ key: 'name', label: '卡种', placeholder: '卡种名称' }];
const formFields: FormField[] = [
  { key: 'name', label: '卡种名称' },
  { key: 'image', label: '图片路径' }
];
const toolbar: RowAction[] = [{ label: '新增卡种', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该卡种？',
    handler: row => store.remove('giftCards', row.id, '营销中心', 'name')
  }
];
const config: AdminListConfig = {
  title: '礼品卡',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.giftCards, search, page, pageSize),
  form: {
    title: '礼品卡',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('giftCards', editing.id, data, '营销中心', 'name');
      else store.add('giftCards', data, '营销中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
