<script setup lang="ts">
defineOptions({
  name: 'MarketingExchange'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';
import { fetchAdminExchangeRecords, executeVerifyApi } from '@/service/api/crud';

const statusMapDef = statusMap({
  PENDING: ['待核销', 'info'],
  VERIFIED: ['已核销', 'success'],
  COMPLETED: ['已完成', 'success']
});

const columns: DataTableColumns<any> = [
  { title: '兑换单号', key: 'recordNo', width: 145 },
  { title: '用户', key: 'user', width: 120 },
  { title: '商品', key: 'product', minWidth: 180 },
  { title: '数量', key: 'quantity', width: 70, align: 'right' },
  { title: '时光币', key: 'points', width: 90, align: 'right' },
  { title: '状态', key: 'status', width: 100, render: renderTag('status', statusMapDef) },
  { title: '申请时间', key: 'applyTime', render: renderDateTime('applyTime'), width: 145 }
];

const searchFields: SearchField[] = [
  { key: 'recordNo', label: '兑换单号', placeholder: '兑换单号' },
  { key: 'user', label: '用户', placeholder: '用户昵称' },
  { key: 'product', label: '商品', placeholder: '商品名称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待核销', value: 'PENDING' },
      { label: '已核销', value: 'VERIFIED' },
      { label: '已完成', value: 'COMPLETED' }
    ]
  }
];

const rowActions: RowAction[] = [
  {
    label: '门店核销',
    type: 'success',
    reasonPrompt: '确认门店核销该兑换？（请填写备注）',
    handler: async (row, _reason) => {
      const code = String(row.pickupCode || '').trim();
      if (!code) {
        window.$message?.error('该兑换单缺少自提码，无法核销');
        return;
      }
      await executeVerifyApi({ type: 'EXCHANGE', code });
    },
    visible: row => row.status === 'PENDING' && !!row.pickupCode
  }
];

const config: AdminListConfig = {
  title: '兑换记录',
  columns,
  searchFields,
  toolbar: [],
  rowActions,
  loadData: async ({ page, pageSize, search }) => {
    const res = await fetchAdminExchangeRecords({
      current: page,
      size: pageSize,
      recordNo: search.recordNo,
      user: search.user,
      product: search.product,
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
