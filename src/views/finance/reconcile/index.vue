<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  {
    title: '异常类型',
    key: 'issueType',
    width: 120,
    render: renderTag(
      'issueType',
      statusMap({
        金额差异: ['金额差异', 'error'],
        状态不一致: ['状态不一致', 'warning'],
        缺失流水: ['缺失流水', 'info']
      })
    )
  },
  { title: '订单号', key: 'orderNo', width: 160 },
  { title: '系统值', key: 'systemValue', width: 110 },
  { title: '三方值', key: 'thirdValue', width: 110 },
  { title: '差异金额(元)', key: 'diffAmount', width: 120, align: 'right' as const, render: renderMoney('diffAmount') },
  { title: '发现时间', key: 'foundTime', width: 180 },
  {
    title: '处理状态',
    key: 'status',
    width: 110,
    render: renderTag('status', statusMap({ open: ['待处理', 'warning'], resolved: ['已处理', 'success'] }))
  }
];
const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待处理', value: 'open' },
      { label: '已处理', value: 'resolved' }
    ]
  }
];
const toolbar: RowAction[] = [
  { label: '重新对账', type: 'primary', handler: () => window.$message?.success('已触发重新对账') }
];
const rowActions: RowAction[] = [
  {
    label: '标记处理',
    type: 'success',
    handler: row => store.patch('reconciles', row.id, { status: 'resolved' }, '财务中心', '标记处理', 'orderNo')
  }
];
const config: AdminListConfig = {
  title: '对账异常池',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.reconciles, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
