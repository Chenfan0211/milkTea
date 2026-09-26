<script setup lang="ts">

defineOptions({
  name: 'trade_verify-pool'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig } from '@/views/_shared/types';
import type { SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderMoney, renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();
const verifyTypeMap = statusMap({ order: ['订单', 'success'], exchange: ['兑换', 'warning'] });
const columns: DataTableColumns<any> = [
  { title: '取餐码', key: 'pickupCode', width: 120 },
  { title: '类型', key: 'type', width: 90, render: renderTag('type', verifyTypeMap) },
  { title: '订单号', key: 'orderNo', width: 150 },
  { title: '商品', key: 'product', minWidth: 180 },
  { title: '规格', key: 'spec', minWidth: 140 },
  {
    title: '金额',
    key: 'amount',
    width: 120,
    align: 'right',
    render: row => (row.type === 'exchange' ? `${row.points || 0} 时光币` : renderMoney('amount')(row))
  }
];
const searchFields: SearchField[] = [
  // 后端 verify-pool 只有一个 search 参数（同时匹配取餐码/订单号/兑换单号），
  // 这里合并成一项，避免「填了取餐码又填了订单号」时语义冲突。
  { key: 'search', label: '取餐码/单号', placeholder: '取餐码 / 订单号' },
  {
    key: 'type',
    label: '类型',
    type: 'select',
    options: [
      { label: '订单', value: 'order' },
      { label: '兑换', value: 'exchange' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  {
    label: '执行核销',
    type: 'success',
    reasonPrompt: '确认核销该单据？（请填写备注）',
    // 必须传整行：待核销池是服务端分页，本地镜像没有这行数据，
    // 按 id 反查会拿到 undefined 而静默失败（历史 Bug）。
    handler: (row, reason) => store.executeVerify(row, reason)
  }
];
const config: AdminListConfig = {
  title: '待核销池',
  remoteKey: 'verifyPool',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('verifyPool', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

