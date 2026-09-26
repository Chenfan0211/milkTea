<script setup lang="ts">

defineOptions({
  name: 'finance_snapshot'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DetailGroup } from '@/views/_shared/detail-types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { fetchAdminSnapshotDetail } from '@/service/api/finance';
import { renderTag, statusMap, renderDateTime, formatDateTime } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '快照号', key: 'snapshotNo', width: 140 },
  { title: '订单号', key: 'orderNo', width: 150 },
  { title: '商品信息', key: 'summary', minWidth: 130, render: (row: any) => row.summary || '—' },
  { title: '供应商', key: 'supplierAmount', width: 90, align: 'right', render: (row: any) => `¥${(row.supplierAmount ?? 0).toFixed(2)}` },
  { title: '门店', key: 'storeAmount', width: 90, align: 'right', render: (row: any) => `¥${(row.storeAmount ?? 0).toFixed(2)}` },
  { title: '资源方', key: 'channelAmount', width: 90, align: 'right', render: (row: any) => `¥${(row.channelAmount ?? 0).toFixed(2)}` },
  { title: '投资人', key: 'investorAmount', width: 90, align: 'right', render: (row: any) => `¥${(row.investorAmount ?? 0).toFixed(2)}` },
  { title: '平台分佣', key: 'platformCommission', width: 88, align: 'right', render: (row: any) => `¥${(row.platformCommission ?? 0).toFixed(2)}` },
  { title: '平台提成', key: 'platformBonus', width: 88, align: 'right', render: (row: any) => `¥${(row.platformBonus ?? 0).toFixed(2)}` },
  { title: '平台合计', key: 'platformAmount', width: 88, align: 'right', render: (row: any) => `¥${(row.platformAmount ?? 0).toFixed(2)}` },
  {
    title: '合计校验',
    key: 'totalCheck',
    width: 88,
    render: renderTag('totalCheck', statusMap({ 一致: ['一致', 'success'], 不一致: ['不一致', 'error'] }))
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: renderTag('status', statusMap({ valid: ['有效', 'success'], invalid: ['已作废', 'default'] }))
  },
  { title: '创建时间', key: 'createTime', render: renderDateTime('createTime'), width: 145 }
];
const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '有效', value: 'valid' },
      { label: '已作废', value: 'invalid' }
    ]
  }
];
const toolbar: RowAction[] = [];

const snapshotStatusLabel = (v: string) => ({ valid: '有效', invalid: '已作废' } as Record<string, string>)[v] ?? v;
const totalCheckLabel = (v: string) =>
  ({ 一致: '一致', 不一致: '不一致', ok: '一致', mismatch: '不一致' } as Record<string, string>)[v] ?? v;
const money = (v: any) => `¥${(Number(v) || 0).toFixed(2)}`;

/** 分账快照详情弹层字段（原 /finance/snapshot-detail 页面口径） */
const detailGroups: DetailGroup[] = [
  {
    title: '基础信息',
    fields: [
      { label: '快照号', key: 'snapshotNo' },
      { label: '订单号', key: 'orderNo' },
      { label: '商品信息', render: (r: any) => r.summary || '—' },
      { label: '商品件数', render: (r: any) => String(r.itemCount ?? 0) },
      { label: '合计校验', render: (r: any) => totalCheckLabel(r.totalCheck) },
      { label: '状态', render: (r: any) => snapshotStatusLabel(r.status) },
      { label: '创建时间', render: (r: any) => formatDateTime(r.createTime) }
    ]
  },
  {
    title: '分账金额（元）',
    fields: [
      { label: '供应商（成本）', render: (r: any) => money(r.supplierAmount) },
      { label: '门店', render: (r: any) => money(r.storeAmount) },
      { label: '资源方', render: (r: any) => money(r.channelAmount) },
      { label: '投资人', render: (r: any) => money(r.investorAmount) },
      { label: '平台分佣', render: (r: any) => money(r.platformCommission) },
      { label: '平台提成', render: (r: any) => money(r.platformBonus) },
      { label: '平台合计', render: (r: any) => money(r.platformAmount) }
    ]
  }
];

const rowActions: RowAction[] = [
  {
    label: '详情',
    type: 'info',
    // 弹层展示：按 id 拉取快照详情（含商品摘要），不跳转新页面
    detail: {
      title: '分账快照详情',
      groups: detailGroups,
      load: (row: any) => fetchAdminSnapshotDetail(row.id)
    }
  }
];
const config: AdminListConfig = {
  title: '分账快照',
  remoteKey: 'snapshots',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('snapshots', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

