<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();

const moneyCol = (key: string, title: string) => ({
  title,
  key,
  width: 90,
  align: 'right' as const,
  render: renderMoney(key)
});
const columns: DataTableColumns<any> = [
  { title: '快照号', key: 'snapshotNo', width: 140 },
  { title: '订单号', key: 'orderNo', width: 160 },
  moneyCol('platformAmount', '平台'),
  moneyCol('storeAmount', '门店'),
  moneyCol('channelAmount', '渠道'),
  moneyCol('investorAmount', '投资人'),
  moneyCol('supplierAmount', '供应商'),
  {
    title: '合计校验',
    key: 'totalCheck',
    width: 110,
    render: renderTag('totalCheck', statusMap({ 一致: ['一致', 'success'], 不一致: ['不一致', 'error'] }))
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag('status', statusMap({ valid: ['有效', 'success'], invalid: ['已作废', 'default'] }))
  },
  { title: '创建时间', key: 'createTime', width: 180 }
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
const toolbar: RowAction[] = [
  {
    label: '生成新快照',
    type: 'primary',
    handler: () => {
      const o = store.orders[0];
      if (o)
        store.add(
          'snapshots',
          {
            snapshotNo: `SN${Date.now()}`,
            orderNo: o.orderNo,
            platformAmount: 189,
            storeAmount: 945,
            channelAmount: 284,
            investorAmount: 284,
            supplierAmount: 188,
            totalCheck: '一致',
            status: 'valid'
          },
          '财务中心',
          'snapshotNo'
        );
    }
  }
];
const rowActions: RowAction[] = [
  {
    label: '详情',
    type: 'info',
    handler: row => window.$message?.info(`快照 ${row.snapshotNo} 合计校验：${row.totalCheck}`)
  },
  {
    label: '重新分账',
    type: 'warning',
    confirm: '改分账需审批并生成新快照，确认提交？',
    handler: row => store.patch('snapshots', row.id, { status: 'invalid' }, '财务中心', '作废快照', 'snapshotNo')
  }
];
const config: AdminListConfig = {
  title: '分账快照',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.snapshots, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
