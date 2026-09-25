<script setup lang="ts">

defineOptions({
  name: 'marketing_points'
});

import { h } from 'vue';
import { NImage } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

const categoryLabel = (v: string) => (v === 'pet' ? '宠物公益专区' : v === 'coupon' ? '优惠券区' : v || '—');

const columns: DataTableColumns<any> = [
  {
    title: '图片',
    key: 'image',
    width: 80,
    render: (row: any) =>
      row.image
        ? h(NImage, { src: row.image, width: 56, height: 56, objectFit: 'cover', style: 'border-radius:6px' })
        : h('span', { style: 'color:#9B9B96' }, '—')
  },
  { title: '商品名称', key: 'name', minWidth: 200 },
  { title: '分类', key: 'category', width: 120, render: (row: any) => categoryLabel(row.category) },
  { title: '所需积分', key: 'points', width: 100, align: 'right' },
  { title: '库存', key: 'stock', width: 90, align: 'right' },
  { title: '角标', key: 'badge', width: 110, render: (row: any) => row.badge || '—' },
  { title: '限购说明', key: 'limitText', minWidth: 150, render: (row: any) => row.limitText || '—' }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '商品', placeholder: '商品名称' },
  {
    key: 'category',
    label: '分类',
    type: 'select',
    options: [
      { label: '宠物公益专区', value: 'pet' },
      { label: '优惠券区', value: 'coupon' }
    ]
  }
];

const formFields: FormField[] = [
  { key: 'image', label: '商品图片', type: 'image' },
  { key: 'name', label: '商品名称' },
  {
    key: 'category',
    label: '分类',
    type: 'select',
    options: [
      { label: '宠物公益专区', value: 'pet' },
      { label: '优惠券区', value: 'coupon' }
    ]
  },
  { key: 'points', label: '所需积分', type: 'number' },
  { key: 'stock', label: '库存', type: 'number' },
  { key: 'badge', label: '角标' },
  { key: 'limitText', label: '限购说明' },
  { key: 'description', label: '商品描述', type: 'textarea' }
];

const toolbar: RowAction[] = [{ label: '新增兑换商品', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该商品？（请填写备注）',
    handler: async (row, reason) => await store.remove('pointsProducts', row.id, '营销中心', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '积分商城',
  remoteKey: 'pointsProducts',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('pointsProducts', search, page, pageSize),
  form: {
    title: '兑换商品',
    fields: formFields,
    onSubmit: async (data, editing) => {
      if (editing) await store.update('pointsProducts', editing.id, data, '营销中心', 'name');
      else await store.add('pointsProducts', data, '营销中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

