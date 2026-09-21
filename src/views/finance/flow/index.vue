<script setup lang="ts">

defineOptions({
  name: 'finance_flow'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useRoute } from 'vue-router';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();
const route = useRoute();

const roleLabel = (v: string) =>
  (({ platform: '平台', store: '门店', resource: '资源方', investor: '投资人', supplier: '供应商' }) as Record<string, string>)[v] ?? v;

const typeMap = statusMap({
  INCOME: ['订单入账', 'success'],
  WITHDRAW: ['提现出款', 'warning'],
  REFUND: ['退款', 'error'],
  FREEZE: ['冻结', 'info'],
  UNFREEZE: ['解冻', 'primary'],
  ADJUST: ['调整', 'default']
});

const columns: DataTableColumns<any> = [
  { title: '流水号', key: 'flowNo', width: 150 },
  {
    title: '类型',
    key: 'type',
    width: 88,
    render: renderTag('type', typeMap)
  },
  { title: '方向', key: 'direction', width: 70, render: (row: any) => (row.direction === 'in' ? '入' : '出') },
  { title: '金额(元)', key: 'amount', width: 88, align: 'right', render: renderMoney('amount') },
  { title: '经营方', key: 'subjectName', width: 120, render: (row: any) => row.subjectName || '—' },
  { title: '关联订单', key: 'orderNo', width: 125, render: (row: any) => row.orderNo || '—' },
  { title: '变动后池子余额(元)', key: 'poolBalanceAfter', width: 120, align: 'right', render: renderMoney('poolBalanceAfter') },
  { title: '备注', key: 'remark', minWidth: 160, render: (row: any) => row.remark || '—' },
  { title: '时间', key: 'createTime', width: 145 }
];

const searchFields: SearchField[] = [
  {
    key: 'subjectId',
    label: '经营方',
    type: 'select',
    options: () => store.subjectAccounts.map((a: any) => ({ label: `${a.subjectName}（${roleLabel(a.roleType)}）`, value: String(a.subjectId) }))
  },
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  {
    key: 'type',
    label: '类型',
    type: 'select',
    options: [
      { label: '订单入账', value: 'INCOME' },
      { label: '提现出款', value: 'WITHDRAW' },
      { label: '退款', value: 'REFUND' },
      { label: '冻结', value: 'FREEZE' },
      { label: '解冻', value: 'UNFREEZE' }
    ]
  }
];

const config: AdminListConfig = {
  title: '资金流水',
  remoteKey: 'fundFlows',
  initialSearch: route.query.subjectId ? { subjectId: String(route.query.subjectId) } : {},
  columns,
  searchFields,
  toolbar: [],
  rowActions: [],
  loadData: async ({ page, pageSize, search }) => {
    const { subjectId: searchSubjectId, ...restSearch } = search;
    const subjectId = searchSubjectId ?? route.query.subjectId;
    let list = store.fundFlows;
    if (subjectId) list = list.filter((f: any) => String(f.subjectId) === String(subjectId));
    return store.listFiltered(list, restSearch, page, pageSize);
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

