<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '主体', key: 'subject', minWidth: 140 },
  { title: '角色', key: 'role', width: 100 },
  { title: '订单号', key: 'orderNo', width: 160 },
  { title: '金额(元)', key: 'amount', width: 110, align: 'right' as const, render: renderMoney('amount') },
  {
    title: '资金状态',
    key: 'status',
    width: 110,
    render: renderTag(
      'status',
      statusMap({
        PENDING: ['待结算', 'info'],
        SETTLEABLE: ['可结算', 'primary'],
        FROZEN: ['冻结', 'warning'],
        SETTLED: ['已结算', 'success']
      })
    )
  },
  { title: 'T+1 结转', key: 'carryTime', width: 180 },
  { title: '流水号', key: 'flowNo', width: 150 }
];
const searchFields: SearchField[] = [
  { key: 'subject', label: '主体', placeholder: '主体名称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待结算', value: 'PENDING' },
      { label: '可结算', value: 'SETTLEABLE' },
      { label: '冻结', value: 'FROZEN' },
      { label: '已结算', value: 'SETTLED' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  {
    label: '冻结',
    type: 'warning',
    confirm: '确认冻结该笔资金？',
    handler: row => store.freezeLedger(row.id),
    visible: row => !['FROZEN', 'SETTLED'].includes(row.status)
  },
  {
    label: '解冻',
    type: 'warning',
    confirm: '确认解冻该笔资金？',
    handler: row => store.unfreezeLedger(row.id),
    visible: row => row.status === 'FROZEN'
  }
];
const config: AdminListConfig = {
  title: '资金台账',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.ledgers, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
