<script setup lang="ts">

defineOptions({
  name: 'system_audit'
});

import { computed } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderDateTime } from '@/views/_shared/render';

const store = useAdminStore();

const moduleOptions = computed(() => {
  const set = new Set<string>();
  for (const row of store.auditLogs) {
    if (row.module) set.add(row.module);
  }
  return Array.from(set).map(m => ({ label: m, value: m }));
});

const moduleLabels: Record<string, string> = {
  AUTH: '鉴权',
  PAY: '支付',
  WITHDRAW: '提现'
};

const actionLabels: Record<string, string> = {
  LOGIN: '登录',
  LOGIN_FAIL: '登录失败',
  LOGIN_FAIL_LOCKED: '登录失败并锁定',
  LOGIN_BLOCKED: '登录拦截',
  CALLBACK: '支付回调',
  CALLBACK_REJECT: '回调拒绝',
  ADMIN_APPLY: '后台代发起提现',
  APPROVE: '审核通过',
  REJECT: '审核驳回',
  MARK_FAILED: '标记出款失败',
  ADD: '新增',
  EDIT: '编辑',
  DELETE: '删除',
  BIND: '绑定',
  UNBIND: '解绑',
  ENABLE: '启用',
  DISABLE: '停用'
};

function moduleText(v: any) {
  const key = String(v || '').toUpperCase();
  return moduleLabels[key] ?? v ?? '—';
}

function actionText(v: any) {
  const key = String(v || '').toUpperCase();
  return actionLabels[key] ?? v ?? '—';
}

const targetOptions = computed(() => {
  const set = new Set<string>();
  for (const row of store.auditLogs) {
    if (row.target) set.add(String(row.target));
  }
  return Array.from(set).map(v => ({ label: v, value: v }));
});

const columns: DataTableColumns<any> = [
  { title: '操作时间', key: 'createTime', render: renderDateTime('createTime'), width: 175 },
  { title: '操作人', key: 'operator', width: 110 },
  { title: '模块', key: 'module', width: 100, render: (row: any) => moduleText(row.module) },
  { title: '操作类型', key: 'action', width: 130, render: (row: any) => actionText(row.action) },
  { title: '操作对象', key: 'target', width: 180 },
  {
    title: '旧值',
    key: 'beforeValue',
    minWidth: 200,
    render: (row: any) => row.beforeValue || '—'
  },
  {
    title: '新值',
    key: 'afterValue',
    minWidth: 200,
    render: (row: any) => row.afterValue || '—'
  },
  {
    title: '备注',
    key: 'reason',
    minWidth: 160,
    render: (row: any) => row.reason || '—'
  }
];

const searchFields: SearchField[] = [
  { key: 'operator', label: '操作人', placeholder: '操作人' },
  { key: 'module', label: '模块', type: 'select', options: () => moduleOptions.value, placeholder: '请选择模块' },
  { key: 'target', label: '操作对象', type: 'select', options: () => targetOptions.value, placeholder: '请选择操作对象' },
  { key: 'changeValue', label: '旧值/新值', placeholder: '输入旧值或新值' }
];

const config: AdminListConfig = {
  title: '审计日志',
  remoteKey: 'auditLogs',
  columns,
  searchFields,
  toolbar: [],
  rowActions: [],
  loadData: async ({ page, pageSize, search }) => {
    const { changeValue, ...rest } = search || {};
    const keyword = String(changeValue || '').trim();
    if (keyword) {
      const [before, after] = await Promise.all([
        store.queryRemote('auditLogs', { ...rest, beforeValue: keyword }, page, pageSize),
        store.queryRemote('auditLogs', { ...rest, afterValue: keyword }, page, pageSize)
      ]);
      const merged = new Map<any, any>();
      for (const row of [...before.data, ...after.data]) merged.set(row.id, row);
      const rows = Array.from(merged.values()).sort((x: any, y: any) => String(y.createTime || '').localeCompare(String(x.createTime || '')));
      return { data: rows, total: Math.max(before.total, after.total) };
    }
    return store.queryRemote('auditLogs', rest, page, pageSize);
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>

