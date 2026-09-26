<script setup lang="ts">
defineOptions({
  name: 'finance_flow'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useRoute } from 'vue-router';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, formatFen, renderDateTime } from '@/views/_shared/render';
import { fetchFinanceFlows } from '@/service/api/crud';

const store = useAdminStore();
const route = useRoute();

const roleLabel = (v: string) =>
  (
    ({ platform: '平台', store: '门店', resource: '资源方', investor: '投资人', supplier: '供应商' }) as Record<
      string,
      string
    >
  )[v] ?? v;

const typeMap = statusMap({
  INCOME: ['入账', 'success'],
  WITHDRAW: ['提现', 'warning'],
  REFUND: ['退款', 'error'],
  FREEZE: ['冻结', 'info'],
  UNFREEZE: ['解冻', 'primary']
});

const missing = '历史数据缺失';
const textOrMissing = (value: any) => (value == null || value === '' ? missing : String(value));
const moneyOrMissing = (value: any) => (value == null ? missing : formatFen(Number(value)));
const isOrderFlow = (row: any) =>
  row.orderId != null || row.orderNo || row.bizType === 'ORDER' || row.bizType === 'REFUND';
const renderSettlementStatus = (row: any) => {
  if (row.settlementStatusName) return row.settlementStatusName;
  if (!isOrderFlow(row)) return '不适用';
  return missing;
};

const columns: DataTableColumns<any> = [
  { title: '账户', key: 'accountName', minWidth: 180, render: (row: any) => textOrMissing(row.accountName) },
  {
    title: '类型',
    key: 'type',
    width: 88,
    render: renderTag('type', typeMap)
  },
  {
    title: '结算状态',
    key: 'settlementStatus',
    width: 100,
    render: renderSettlementStatus
  },
  {
    title: '关联订单/业务单号',
    key: 'bizNo',
    minWidth: 180,
    render: (row: any) => textOrMissing(row.orderNo || row.bizNo)
  },
  {
    title: '余额口径',
    key: 'balanceBucketName',
    width: 100,
    render: (row: any) => textOrMissing(row.balanceBucketName)
  },
  {
    title: '变动前金额(元)',
    key: 'balanceBefore',
    width: 120,
    align: 'right',
    render: (row: any) => moneyOrMissing(row.balanceBefore)
  },
  {
    title: '变动金额(元)',
    key: 'changeAmount',
    width: 110,
    align: 'right',
    render: (row: any) => moneyOrMissing(row.changeAmount)
  },
  {
    title: '变动后金额(元)',
    key: 'balanceAfter',
    width: 120,
    align: 'right',
    render: (row: any) => moneyOrMissing(row.balanceAfter)
  },
  { title: '备注', key: 'remark', minWidth: 160, render: (row: any) => row.remark || missing },
  { title: '时间', key: 'createTime', width: 170, render: renderDateTime('createTime') }
];

const searchFields: SearchField[] = [
  {
    key: 'subjectId',
    label: '经营方',
    type: 'select',
    options: () =>
      store.subjectAccounts.map((a: any) => ({
        label: `${a.subjectName}（${roleLabel(a.roleType)}）`,
        value: String(a.subjectId)
      }))
  },
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  {
    key: 'type',
    label: '类型',
    type: 'select',
    options: [
      { label: '入账', value: 'INCOME' },
      { label: '提现', value: 'WITHDRAW' },
      { label: '退款', value: 'REFUND' },
      { label: '冻结', value: 'FREEZE' },
      { label: '解冻', value: 'UNFREEZE' }
    ]
  },
  {
    key: 'settled',
    label: '结算状态',
    type: 'select',
    options: [
      { label: '已结算', value: 'true' },
      { label: '未结算', value: 'false' }
    ]
  }
];

const config: AdminListConfig = {
  title: '资金流水',
  // 「经营方」筛选下拉与列表列都按 subjectId 解析名称，需预加载 subjects / subjectAccounts
  remoteDeps: ['subjects', 'subjectAccounts'],
  initialSearch: route.query.subjectId ? { subjectId: String(route.query.subjectId) } : {},
  columns,
  searchFields,
  toolbar: [],
  rowActions: [],
  loadData: async ({ page, pageSize, search }) => {
    const subjectId = search.subjectId ?? route.query.subjectId;
    const result = await fetchFinanceFlows({
      current: page,
      size: pageSize,
      subjectId: subjectId || undefined,
      type: search.type || undefined,
      orderNo: search.orderNo || undefined,
      settled: search.settled || undefined
    });
    return { data: result.records, total: result.total };
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
