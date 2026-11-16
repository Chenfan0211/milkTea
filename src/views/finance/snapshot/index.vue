<script setup lang="ts">
defineOptions({
  name: 'FinanceSnapshot'
});

import { h } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DetailGroup } from '@/views/_shared/detail-types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { fetchAdminSnapshotDetail } from '@/service/api/finance';
import { renderTag, statusMap, renderDateTime, formatDateTime, formatFen } from '@/views/_shared/render';
import { splitComposition } from './split-parties';
import { NTag } from 'naive-ui';

const store = useAdminStore();
const money = (v: any) => `¥${formatFen(v)}`;
const formatParties = (parties: ReturnType<typeof splitComposition>['currentParties']) =>
  parties.length ? parties.map(item => `${item.label} ${money(item.amount)}`).join(' · ') : '—';

const columns: DataTableColumns<any> = [
  { title: '快照号', key: 'snapshotNo', width: 140 },
  { title: '订单号', key: 'orderNo', width: 150 },
  {
    title: '订单类型',
    key: 'orderType',
    width: 116,
    render: (row: any) => {
      const composition = splitComposition(row);
      return h(
        NTag,
        { size: 'small', type: composition.isMall ? 'info' : 'default', bordered: false },
        { default: () => composition.orderTypeLabel }
      );
    }
  },
  { title: '商品信息', key: 'summary', minWidth: 130, render: (row: any) => row.summary || '—' },
  {
    title: '供应商',
    key: 'supplierAmount',
    width: 90,
    align: 'right',
    render: (row: any) => `¥${formatFen(row.supplierAmount)}`
  },
  {
    title: '团长',
    key: 'leaderAmount',
    width: 90,
    align: 'right',
    render: (row: any) => `¥${formatFen(row.leaderAmount)}`
  },
  {
    title: '其他分佣方',
    key: 'otherParties',
    minWidth: 190,
    align: 'right',
    render: (row: any) => {
      const composition = splitComposition(row);
      const others = composition.currentParties.filter(
        item => item.key !== 'platform' && item.key !== 'supplier' && item.key !== 'leader'
      );
      if (!composition.isMall) return formatParties(others);
      if (!composition.isLegacyMall) return '—';
      return `历史已结算：${formatParties(composition.legacyParties)}`;
    }
  },
  {
    title: '平台合计',
    key: 'platformAmount',
    width: 88,
    align: 'right',
    render: (row: any) => `¥${formatFen(row.platformAmount)}`
  },
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

const snapshotStatusLabel = (v: string) => (({ valid: '有效', invalid: '已作废' }) as Record<string, string>)[v] ?? v;
const totalCheckLabel = (v: string) =>
  (({ 一致: '一致', 不一致: '不一致', ok: '一致', mismatch: '不一致' }) as Record<string, string>)[v] ?? v;
const orderTypeText = (row: any) => splitComposition(row).orderTypeLabel;
const currentPartyText = (row: any) => formatParties(splitComposition(row).currentParties);
const legacyPartyText = (row: any) => {
  const composition = splitComposition(row);
  return composition.isLegacyMall ? formatParties(composition.legacyParties) : '—';
};

/** 分账快照详情弹层字段（原 /finance/snapshot-detail 页面口径） */
const detailGroups: DetailGroup[] = [
  {
    title: '基础信息',
    fields: [
      { label: '快照号', key: 'snapshotNo' },
      { label: '订单号', key: 'orderNo' },
      { label: '订单类型', render: (r: any) => orderTypeText(r) },
      { label: '当前口径', render: (r: any) => (r.orderType === 'MALL' ? '商城三方分账' : '门店订单分账') },
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
      { label: '当前分佣关系', render: (r: any) => currentPartyText(r) },
      { label: '平台分佣', render: (r: any) => money(r.platformCommission) },
      { label: '平台提成', render: (r: any) => money(r.platformBonus) },
      { label: '平台合计', render: (r: any) => money(r.platformAmount) },
      { label: '历史已结算调整', render: (r: any) => legacyPartyText(r) }
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
  refreshOnEnter: false,
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
