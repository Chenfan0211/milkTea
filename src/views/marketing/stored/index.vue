<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();
const columns: DataTableColumns<any> = [
  { title: '套餐金额(元)', key: 'amount', width: 140, align: 'right' },
  {
    title: '赠送券',
    key: 'coupons',
    minWidth: 260,
    render: (row: any) => (row.coupons || []).map((c: any) => `${c.amount}元x${c.quantity}`).join('、') || '—'
  }
];
const searchFields: SearchField[] = [{ key: 'amount', label: '金额', placeholder: '金额' }];
const formFields: FormField[] = [{ key: 'amount', label: '套餐金额(元)', type: 'number' }];
const toolbar: RowAction[] = [{ label: '新增套餐', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '删除',
    type: 'error',
    confirm: '确认删除该套餐？',
    handler: row => store.remove('storedValuePackages', row.id, '营销中心', 'amount')
  }
];
const config: AdminListConfig = {
  title: '储值套餐',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.storedValuePackages, search, page, pageSize),
  form: {
    title: '储值套餐',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('storedValuePackages', editing.id, data, '营销中心', 'amount');
      else store.add('storedValuePackages', { ...data, coupons: [] }, '营销中心', 'amount');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
