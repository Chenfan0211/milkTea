<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '订单号', key: 'orderNo', width: 160 },
  { title: '门店', key: 'store', width: 130 },
  { title: '用户', key: 'user', width: 110 },
  { title: '商品摘要', key: 'summary', minWidth: 160 },
  { title: '实付金额(元)', key: 'paidAmount', width: 120, align: 'right', render: renderMoney('paidAmount') },
  {
    title: '订单状态',
    key: 'status',
    width: 120,
    render: renderTag(
      'status',
      statusMap({
        CREATED: ['待支付', 'info'],
        PAID: ['已支付', 'primary'],
        VERIFIED: ['已核销', 'success'],
        COMPLETED: ['已完成', 'success'],
        REFUNDED: ['已退款', 'default']
      })
    )
  },
  { title: '取餐码', key: 'pickupCode', width: 100 },
  { title: '创建时间', key: 'createTime', width: 180 }
];
const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  { key: 'store', label: '门店', placeholder: '门店' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待支付', value: 'CREATED' },
      { label: '已支付', value: 'PAID' },
      { label: '已核销', value: 'VERIFIED' },
      { label: '已完成', value: 'COMPLETED' },
      { label: '已退款', value: 'REFUNDED' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  { label: '详情', type: 'info', handler: row => window.$message?.info(`订单 ${row.orderNo} 摘要：${row.summary}`) },
  {
    label: '整单退款',
    type: 'warning',
    confirm: '仅未核销、未结算订单可退款，确认退款？',
    handler: row => store.refundOrder(row.id)
  }
];
const config: AdminListConfig = {
  title: '订单管理',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.orders, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
