<script setup lang="ts">

defineOptions({
  name: 'marketing_coupon'
});

import { h } from 'vue';
import { NImage } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  {
    title: '券面图',
    key: 'image',
    width: 80,
    render: (row: any) =>
      row.image
        ? h(NImage, { src: row.image, width: 56, height: 40, objectFit: 'cover', style: 'border-radius:4px' })
        : h('span', { style: 'color:#9B9B96' }, '—')
  },
  { title: '券标题', key: 'title', minWidth: 200 },
  {
    title: '类型',
    key: 'type',
    width: 90,
    render: (row: any) => (row.type === 'voucher' ? '代金券' : '折扣券')
  },
  { title: '面额(元)', key: 'amount', width: 90, align: 'right' },
  { title: '使用条件', key: 'condition', width: 110 },
  { title: '品牌', key: 'brand', width: 100, render: (row: any) => row.brand || '—' },
  { title: '使用场景', key: 'scenes', minWidth: 160, render: (row: any) => row.scenes || '—' },
  { title: '有效期', key: 'expiryText', width: 150 },
  { title: '来源', key: 'source', width: 100, render: (row: any) => row.source || '—' },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: renderTag('status', statusMap({ enabled: ['启用', 'success'], disabled: ['停用', 'default'] }))
  }
];

const searchFields: SearchField[] = [
  { key: 'title', label: '券标题', placeholder: '券标题' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '启用', value: 'enabled' },
      { label: '停用', value: 'disabled' }
    ]
  }
];

const formFields: FormField[] = [
  { key: 'image', label: '券面图', type: 'image' },
  { key: 'title', label: '券标题' },
  {
    key: 'type',
    label: '类型',
    type: 'select',
    options: [
      { label: '代金券', value: 'voucher' },
      { label: '折扣券', value: 'discount' }
    ]
  },
  { key: 'amount', label: '面额(元)', type: 'number' },
  { key: 'condition', label: '使用条件' },
  { key: 'brand', label: '品牌' },
  { key: 'scenes', label: '使用场景' },
  { key: 'expiryText', label: '有效期(文本)' },
  { key: 'validityPeriod', label: '有效期区间' },
  { key: 'usageTime', label: '使用时段' },
  { key: 'channel', label: '渠道' },
  { key: 'source', label: '来源' },
  {
    key: 'applicableStoreIds',
    label: '适用门店',
    type: 'multiple',
    multiple: true,
    options: () => store.subjects.filter(s => s.type === 'store').map(s => ({ label: s.name, value: s.code }))
  },
  {
    key: 'applicableProductIds',
    label: '适用商品',
    type: 'multiple',
    multiple: true,
    options: () => store.products.map(p => ({ label: p.name, value: p.productId }))
  },
  { key: 'quantity', label: '发放数量', type: 'number' },
  { key: 'paymentRestriction', label: '支付限制' },
  { key: 'description', label: '使用说明', type: 'textarea' }
];

const toolbar: RowAction[] = [{ label: '新增优惠券', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该优惠券？（请填写备注）',
    handler: (row, reason) => store.patch('coupons', row.id, { status: 'disabled' }, '营销中心', '停用', 'title', reason),
    visible: row => row.status === 'enabled'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该优惠券？（请填写备注）',
    handler: (row, reason) => store.patch('coupons', row.id, { status: 'enabled' }, '营销中心', '启用', 'title', reason),
    visible: row => row.status === 'disabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该优惠券？（请填写备注）',
    handler: (row, reason) => store.remove('coupons', row.id, '营销中心', 'title', reason)
  }
];

const config: AdminListConfig = {
  title: '优惠券管理',
  remoteKey: 'coupons',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.coupons, search, page, pageSize),
  form: {
    title: '优惠券',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('coupons', editing.id, data, '营销中心', 'title');
      else store.add('coupons', { ...data, status: 'enabled' }, '营销中心', 'title');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

