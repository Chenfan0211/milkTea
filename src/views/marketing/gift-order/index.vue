<script setup lang="ts">

defineOptions({
  name: 'marketing_gift-order'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const statusMapDef = statusMap({
  CREATED: ['待支付', 'warning'],
  PAID: ['已支付', 'primary'],
  COMPLETED: ['已完成', 'success'],
  REFUNDED: ['已退款', 'default']
});

const columns: DataTableColumns<any> = [
  { title: '订单号', key: 'orderNo', width: 145 },
  { title: '卡种', key: 'cardName', width: 140 },
  { title: '面额(元)', key: 'faceValue', width: 88, align: 'right' },
  { title: '数量', key: 'quantity', width: 70, align: 'right' },
  { title: '金额(元)', key: 'amount', width: 88, align: 'right' },
  { title: '购买人', key: 'buyer', width: 120 },
  { title: '状态', key: 'status', width: 88, render: renderTag('status', statusMapDef) },
  { title: '创建时间', key: 'createTime', width: 145 }
];

const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  { key: 'buyer', label: '购买人', placeholder: '用户昵称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待支付', value: 'CREATED' },
      { label: '已支付', value: 'PAID' },
      { label: '已完成', value: 'COMPLETED' },
      { label: '已退款', value: 'REFUNDED' }
    ]
  }
];

const config: AdminListConfig = {
  title: '礼品卡订单',
  remoteKey: 'giftCardOrders',
  columns,
  searchFields,
  toolbar: [],
  rowActions: [],
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.giftCardOrders, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
