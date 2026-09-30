<script setup lang="ts">
defineOptions({
  name: 'MarketingGiftOrder'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { renderTag, statusMap, renderDateTime, renderMoney } from '@/views/_shared/render';
import { fetchAdminGiftCardOrders } from '@/service/api/crud';

const statusMapDef = statusMap({
  CREATED: ['待支付', 'warning'],
  PAID: ['待核销', 'primary'],
  COMPLETED: ['已完成', 'success'],
  CANCELED: ['已取消', 'default']
});

const columns: DataTableColumns<any> = [
  { title: '订单号', key: 'orderNo', width: 145 },
  { title: '卡种', key: 'cardName', width: 140 },
  { title: '面额(元)', key: 'faceValue', width: 88, align: 'right', render: renderMoney('faceValue') },
  { title: '数量', key: 'quantity', width: 70, align: 'right' },
  { title: '金额(元)', key: 'amount', width: 88, align: 'right', render: renderMoney('amount') },
  { title: '购买人', key: 'buyer', width: 120 },
  { title: '状态', key: 'status', width: 88, render: renderTag('status', statusMapDef) },
  { title: '创建时间', key: 'createTime', width: 170, render: renderDateTime('createTime') }
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
      { label: '待核销', value: 'PAID' },
      { label: '已完成', value: 'COMPLETED' },
      { label: '已取消', value: 'CANCELED' }
    ]
  }
];

const config: AdminListConfig = {
  title: '礼品卡订单',
  columns,
  searchFields,
  toolbar: [],
  rowActions: [],
  loadData: async ({ page, pageSize, search }) => {
    const res = await fetchAdminGiftCardOrders({
      current: page,
      size: pageSize,
      orderNo: search.orderNo,
      buyer: search.buyer,
      status: search.status
    });
    return { data: res.records ?? [], total: res.total ?? 0 };
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
