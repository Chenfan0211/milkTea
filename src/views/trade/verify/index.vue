<script setup lang="ts">

defineOptions({
  name: 'trade_verify'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DetailGroup } from '@/views/_shared/detail-types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { fetchAdminVerifyDetail } from '@/service/api/trade';
import { renderTag, statusMap, renderDateTime, formatDateTime } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '核销码', key: 'verifyCode', width: 120 },
  { title: '订单号', key: 'orderNo', width: 160 },
  { title: '门店', key: 'store', width: 130 },
  { title: '操作人', key: 'operator', width: 110 },
  { title: '设备', key: 'device', width: 110 },
  {
    title: '核销类型',
    key: 'type',
    width: 88,
    render: renderTag('type', statusMap({ ORDER: ['订单', 'success'], EXCHANGE: ['兑换', 'warning'] }))
  },
  {
    title: '结果',
    key: 'result',
    width: 95,
    render: renderTag('result', statusMap({ success: ['核销成功', 'success'], rejected: ['重复拦截', 'error'] }))
  },
  { title: '时间', key: 'time', width: 170, render: renderDateTime('time') }
];
const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  {
    key: 'type',
    label: '核销类型',
    type: 'select',
    options: [
      { label: '订单', value: '订单' },
      { label: '兑换', value: '兑换' }
    ]
  },
  {
    key: 'result',
    label: '结果',
    type: 'select',
    options: [
      { label: '核销成功', value: 'success' },
      { label: '重复拦截', value: 'rejected' }
    ]
  }
];
const toolbar: RowAction[] = [];

const resultLabel = (v: string) =>
  (({ SUCCESS: '核销成功', success: '核销成功', FAIL: '核销失败', failed: '核销失败', rejected: '重复拦截' }) as Record<string, string>)[v] ?? v;
const typeLabel = (v: string) => ({ ORDER: '订单', EXCHANGE: '兑换' } as Record<string, string>)[v] ?? v;

/** 核销记录详情弹层字段（原 /trade/verify-detail 页面口径） */
const detailGroups: DetailGroup[] = [
  {
    title: '核销信息',
    fields: [
      { label: '核销码', key: 'verifyCode' },
      { label: '订单号', key: 'orderNo' },
      { label: '门店', render: (r: any) => r.store || '—' },
      { label: '操作人', render: (r: any) => r.operator || '—' },
      { label: '设备', render: (r: any) => r.device || '—' },
      { label: '核销类型', render: (r: any) => typeLabel(r.type) },
      { label: '结果', render: (r: any) => resultLabel(r.result) },
      { label: '时间', render: (r: any) => formatDateTime(r.time ?? r.createTime) }
    ]
  }
];

const rowActions: RowAction[] = [
  {
    label: '详情',
    type: 'info',
    // 弹层展示：按 id 拉取核销记录详情，不跳转新页面
    detail: {
      title: '核销记录详情',
      groups: detailGroups,
      load: (row: any) => fetchAdminVerifyDetail(row.id)
    }
  }
];
const config: AdminListConfig = {
  title: '核销记录',
  remoteKey: 'verifies',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('verifies', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

