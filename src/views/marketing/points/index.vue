<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();
const columns: DataTableColumns<any> = [
  { title: '商品名称', key: 'name', minWidth: 180 },
  { title: '分类', key: 'category', width: 120 },
  { title: '所需积分', key: 'points', width: 110, align: 'right' }
];
const searchFields: SearchField[] = [{ key: 'name', label: '商品', placeholder: '商品名称' }];
const formFields: FormField[] = [
  { key: 'name', label: '商品名称' },
  {
    key: 'category',
    label: '分类',
    type: 'select',
    options: [
      { label: '实物', value: '实物' },
      { label: '优惠券', value: '优惠券' }
    ]
  },
  { key: 'points', label: '积分', type: 'number' }
];
const toolbar: RowAction[] = [{ label: '新增兑换商品', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该商品？',
    handler: row => store.remove('pointsProducts', row.id, '营销中心', 'name')
  }
];
const config: AdminListConfig = {
  title: '积分商城',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.pointsProducts, search, page, pageSize),
  form: {
    title: '兑换商品',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('pointsProducts', editing.id, data, '营销中心', 'name');
      else store.add('pointsProducts', data, '营销中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
