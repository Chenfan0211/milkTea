<script setup lang="ts">
defineOptions({
  name: 'SubjectDeviceBrew'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderDateTime } from '@/views/_shared/render';

/**
 * 冲泡记录（茶机队列上报，只读）。
 *
 * 为什么只读：这些是设备上报的事实数据，人工改动会破坏与设备侧的对账口径。
 * 门店列是「上报时刻」的设备归属快照 —— 设备换绑门店后，历史记录仍显示当时的门店。
 */

const store = useAdminStore();

const storeOptions = () =>
  store.subjects.filter((s: any) => s.type === 'store').map((s: any) => ({ label: s.name, value: String(s.id) }));

/**
 * 日期范围 → 后端范围过滤参数。
 *
 * 通用 CRUD 只支持 ge_（>=）/ lt_（<），因此结束日取「次日 00:00:00」，
 * 保证 23:59:59 当秒的记录不会被漏掉（直接用 23:59:59 会因 < 比较被排除）。
 */
function rangeParams(value: unknown): Record<string, string> {
  if (!Array.isArray(value) || value.length !== 2 || !value[0]) {
    return {};
  }
  const start = `${value[0]} 00:00:00`;
  if (!value[1]) {
    return { ge_baseCreateTime: start };
  }
  const end = new Date(`${value[1]}T00:00:00`);
  if (Number.isNaN(end.getTime())) {
    return { ge_baseCreateTime: start };
  }
  end.setDate(end.getDate() + 1);
  const pad = (n: number) => String(n).padStart(2, '0');
  const endText = `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())} 00:00:00`;
  return { ge_baseCreateTime: start, lt_baseCreateTime: endText };
}

const load = async (p: any) => {
  if (!store.subjects.length) await store.loadRemote('subjects');
  const params: Record<string, any> = { ...rangeParams(p.search?.baseCreateTime) };
  const deviceSn = String(p.search?.deviceSn || '').trim();
  const teaCode = String(p.search?.teaCode || '').trim();
  if (deviceSn) params.deviceSn = deviceSn;
  if (teaCode) params.teaCode = teaCode;
  if (p.search?.storeSubjectId) params.eq_storeSubjectId = String(p.search.storeSubjectId);
  return store.queryRemote('deviceBrewRecords', params, p.page, p.pageSize);
};

const columns: DataTableColumns<any> = [
  { title: '上报时间', key: 'baseCreateTime', width: 175, render: renderDateTime('baseCreateTime') },
  { title: '门店', key: 'storeName', minWidth: 150, render: (row: any) => row.storeName || '未绑定' },
  { title: '设备编码', key: 'deviceSn', width: 185 },
  { title: '设备ID', key: 'deviceName', width: 150, render: (row: any) => row.deviceName || '—' },
  { title: '茶品代码', key: 'teaCode', width: 100 },
  { title: '茶品名称', key: 'teaName', minWidth: 150, render: (row: any) => row.teaName || '—' },
  { title: '客户代码', key: 'customerCode', width: 120, render: (row: any) => row.customerCode || '—' },
  { title: '生产日期', key: 'madeDay', width: 110, render: (row: any) => row.madeDay || '—' },
  { title: '序列号', key: 'serialNumber', width: 100, render: (row: any) => row.serialNumber || '—' }
];

const searchFields: SearchField[] = [
  { key: 'storeSubjectId', label: '门店', type: 'select', options: storeOptions },
  { key: 'deviceSn', label: '设备编码', placeholder: '设备编码（deviceSN）' },
  { key: 'teaCode', label: '茶品代码', placeholder: '茶品代码（teaCode）' },
  { key: 'baseCreateTime', label: '上报时间', type: 'daterange' }
];

const config: AdminListConfig = {
  title: '冲泡记录',
  columns,
  searchFields,
  loadData: load,
  toolbar: [],
  rowActions: []
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
