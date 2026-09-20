<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '商户订单号', key: 'merchantOrderNo', width: 170 },
  { title: '支付单号', key: 'paymentNo', width: 150 },
  { title: '金额(元)', key: 'amount', width: 110, align: 'right', render: renderMoney('amount') },
  { title: '支付渠道', key: 'channel', width: 110 },
  {
    title: '三方状态',
    key: 'thirdStatus',
    width: 120,
    render: renderTag('thirdStatus', statusMap({ SUCCESS: ['成功', 'success'], PENDING: ['处理中', 'warning'] }))
  },
  {
    title: '标准状态',
    key: 'standardStatus',
    width: 120,
    render: renderTag('standardStatus', statusMap({ PAID: ['已支付', 'primary'], PAYING: ['支付中', 'info'] }))
  },
  { title: '回调时间', key: 'callbackTime', width: 180 }
];
const searchFields: SearchField[] = [
  { key: 'merchantOrderNo', label: '订单号', placeholder: '商户订单号' },
  {
    key: 'standardStatus',
    label: '状态',
    type: 'select',
    options: [
      { label: '已支付', value: 'PAID' },
      { label: '支付中', value: 'PAYING' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  {
    label: '主动查询',
    type: 'info',
    handler: row => window.$message?.success(`已查询 ${row.paymentNo}：${row.standardStatus}`)
  },
  {
    label: '异常重试',
    type: 'warning',
    confirm: '确认重新发起三方查询？',
    handler: row =>
      store.patch(
        'payments',
        row.id,
        { standardStatus: 'PAID', thirdStatus: 'SUCCESS' },
        '交易中心',
        '异常重试',
        'paymentNo'
      )
  }
];
const config: AdminListConfig = {
  title: '支付记录',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.payments, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
