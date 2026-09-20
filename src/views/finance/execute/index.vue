<script setup lang="ts">
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

const columns: DataTableColumns<any> = [
  { title: '执行单号', key: 'executeNo', width: 140 },
  { title: '快照号', key: 'snapshotNo', width: 140 },
  { title: '三方请求号', key: 'thirdRequestNo', width: 150 },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: renderTag(
      'status',
      statusMap({
        PENDING: ['待执行', 'info'],
        RUNNING: ['执行中', 'warning'],
        SUCCESS: ['成功', 'success'],
        FAILED: ['失败', 'error'],
        CANCELLED: ['已取消', 'default']
      })
    )
  },
  { title: '执行时间', key: 'executeTime', width: 180 }
];
const searchFields: SearchField[] = [
  { key: 'snapshotNo', label: '快照号', placeholder: '快照号' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待执行', value: 'PENDING' },
      { label: '执行中', value: 'RUNNING' },
      { label: '成功', value: 'SUCCESS' },
      { label: '失败', value: 'FAILED' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  {
    label: '执行',
    type: 'success',
    handler: row => store.patch('executions', row.id, { status: 'SUCCESS' }, '财务中心', '执行分账', 'executeNo')
  },
  {
    label: '重试',
    type: 'warning',
    handler: row => store.patch('executions', row.id, { status: 'RUNNING' }, '财务中心', '重试', 'executeNo')
  },
  {
    label: '补偿',
    type: 'warning',
    handler: row => store.patch('executions', row.id, { status: 'SUCCESS' }, '财务中心', '补偿', 'executeNo')
  },
  {
    label: '人工重放',
    type: 'error',
    confirm: '确认人工重放该分账任务？',
    handler: row => store.patch('executions', row.id, { status: 'SUCCESS' }, '财务中心', '人工重放', 'executeNo')
  }
];
const config: AdminListConfig = {
  title: '分账执行',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.executions, search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
