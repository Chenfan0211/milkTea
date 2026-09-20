<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '商品编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  { title: '分类', key: 'category', width: 110 },
  { title: '规格数', key: 'specCount', width: 90, align: 'right' },
  { title: '价格(元)', key: 'price', width: 110, align: 'right', render: renderMoney('price') },
  { title: '门店', key: 'store', width: 130 },
  {
    title: '上架状态',
    key: 'onSale',
    width: 110,
    render: renderTag('onSale', statusMap({ on: ['已上架', 'success'], off: ['已下架', 'default'] }))
  },
  {
    title: '分账规则',
    key: 'splitReady',
    width: 110,
    render: renderTag('splitReady', statusMap({ ready: ['完整', 'success'], incomplete: ['未完整', 'warning'] }))
  }
];
const searchFields: SearchField[] = [
  { key: 'name', label: '商品', placeholder: '商品名称' },
  {
    key: 'onSale',
    label: '上架状态',
    type: 'select',
    options: [
      { label: '已上架', value: 'on' },
      { label: '已下架', value: 'off' }
    ]
  }
];
const formFields: FormField[] = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
  { key: 'category', label: '分类' },
  { key: 'price', label: '价格(分)', type: 'number' },
  { key: 'store', label: '门店' },
  {
    key: 'onSale',
    label: '上架状态',
    type: 'select',
    options: [
      { label: '已上架', value: 'on' },
      { label: '已下架', value: 'off' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '新增商品', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '上/下架',
    type: 'warning',
    handler: row =>
      store.patch('products', row.id, { onSale: row.onSale === 'on' ? 'off' : 'on' }, '商品中心', '上下架', 'name')
  },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该商品？',
    handler: row => store.remove('products', row.id, '商品中心', 'name')
  }
];
const config: AdminListConfig = {
  title: '商品管理',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.products, search, page, pageSize),
  form: {
    title: '商品',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('products', editing.id, data, '商品中心', 'name');
      else store.add('products', { ...data, specCount: 1, splitReady: 'ready' }, '商品中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
