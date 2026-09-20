<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();
const columns: DataTableColumns<any> = [
  { title: '券标题', key: 'title', minWidth: 220 },
  { title: '类型', key: 'type', width: 90 },
  { title: '面额(元)', key: 'amount', width: 110, align: 'right', render: renderMoney('amount') },
  { title: '使用条件', key: 'condition', width: 120 },
  { title: '有效期', key: 'expiryText', width: 180 },
  { title: '渠道', key: 'channel', width: 110 },
  {
    title: '状态',
    key: 'status',
    width: 100,
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
  { key: 'title', label: '券标题' },
  { key: 'type', label: '类型', type: 'select', options: [{ label: '代金券', value: 'voucher' }] },
  { key: 'amount', label: '面额(分)', type: 'number' },
  { key: 'condition', label: '使用条件' },
  { key: 'expiryText', label: '有效期' },
  { key: 'channel', label: '渠道' }
];
const toolbar: RowAction[] = [{ label: '新增优惠券', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '启/停用',
    type: 'warning',
    handler: row =>
      store.patch(
        'coupons',
        row.id,
        { status: row.status === 'enabled' ? 'disabled' : 'enabled' },
        '营销中心',
        '启停用',
        'title'
      )
  },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该优惠券？',
    handler: row => store.remove('coupons', row.id, '营销中心', 'title')
  }
];
const config: AdminListConfig = {
  title: '优惠券管理',
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
