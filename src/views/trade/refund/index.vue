<script setup lang="ts">

defineOptions({
  name: 'trade_refund'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney, renderDateTime } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '退款单号', key: 'refundNo', width: 150 },
  { title: '原订单', key: 'orderNo', width: 160 },
  { title: '门店', key: 'store', width: 130, render: (row: any) => row.store || '—' },
  { title: '商品信息', key: 'summary', minWidth: 180, render: (row: any) => row.summary || '—' },
  { title: '金额(元)', key: 'amount', width: 110, align: 'right', render: renderMoney('amount') },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: renderTag(
      'status',
      statusMap({
        SUCCESS: ['退款成功', 'success'],
        REFUNDING: ['退款中', 'warning'],
        FAILED: ['退款失败', 'error']
      })
    )
  },
  { title: '申请时间', key: 'applyTime', render: renderDateTime('applyTime'), width: 150 }
];

const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '原订单号' },
  {
    // 等值过滤（eq_ 前缀）：状态列用 LIKE 会误匹配（如 SUCCESS 命中 FAILED 之外），
    // 后端 refunds 专用接口按 status 精确过滤
    key: 'eq_status',
    label: '状态',
    type: 'select',
    options: [
      { label: '退款成功', value: 'SUCCESS' },
      { label: '退款中', value: 'REFUNDING' },
      { label: '退款失败', value: 'FAILED' }
    ]
  }
];

const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  {
    label: '重新退款',
    type: 'warning',
    confirm: '确认重新发起退款？',
    handler: (row: any) => store.retryRefund(row.id),
    visible: row => row.status === 'FAILED'
  }
];

const config: AdminListConfig = {
  title: '退款管理',
  remoteKey: 'refunds',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('refunds', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>