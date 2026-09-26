<script setup lang="ts">
defineOptions({
  name: 'review_role'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DetailGroup } from '@/views/_shared/detail-types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { fetchAdminRoleApplicationDetail } from '@/service/api/finance';
import { renderTag, statusMap, renderDateTime } from '@/views/_shared/render';

const store = useAdminStore();

const roleLabel = (v: string) =>
  (
    ({ store: '门店', investor: '投资人', resource: '资源方', channel: '资源方', supplier: '供应商' }) as Record<
      string,
      string
    >
  )[v] ?? (v ? String(v) : '—');

const columns: DataTableColumns<any> = [
  { title: '申请人', key: 'nickName', width: 140 },
  { title: '用户ID', key: 'userId', width: 120 },
  { title: '姓名', key: 'name', width: 100 },
  { title: '手机号', key: 'phone', width: 130 },
  { title: '申请角色', key: 'roleType', width: 120, render: (row: any) => roleLabel(row.roleType) },
  {
    title: '状态',
    key: 'status',
    width: 95,
    render: renderTag(
      'status',
      statusMap({ PENDING: ['待审核', 'warning'], APPROVED: ['已通过', 'success'], REJECTED: ['已驳回', 'error'] })
    )
  },
  { title: '申请时间', key: 'applyTime', render: renderDateTime('applyTime'), width: 150 },
  { title: '审核人', key: 'reviewer', width: 95, render: (row: any) => row.reviewer || '—' }
];

const searchFields: SearchField[] = [
  { key: 'nickName', label: '申请人', placeholder: '昵称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待审核', value: 'PENDING' },
      { label: '已通过', value: 'APPROVED' },
      { label: '已驳回', value: 'REJECTED' }
    ]
  }
];

const toolbar: RowAction[] = [];

/** 申请角色/状态中英兜底（与列表列口径一致） */
const applyStatusLabel = (v: string) =>
  (({ pending: '待审核', approved: '已通过', rejected: '已驳回' }) as Record<string, string>)[String(v).toLowerCase()] ?? v;

/** extra_form 是 JSON 字符串，解析失败时返回空对象，避免详情抛错 */
function parseExtra(raw: any): Record<string, any> {
  if (!raw) return {};
  if (typeof raw === 'object') return raw as Record<string, any>;
  try {
    return JSON.parse(String(raw));
  } catch {
    return {};
  }
}

/** 角色开通审核详情弹层字段（原 /review/role-detail 页面口径） */
const detailGroups: DetailGroup[] = [
  {
    title: '申请信息',
    fields: [
      { label: '申请人', render: (r: any) => r.nickName || r.name || '—' },
      { label: '用户ID', render: (r: any) => (r.userId == null ? '—' : String(r.userId)) },
      { label: '姓名', render: (r: any) => r.name || r.nickName || '—' },
      { label: '手机号', render: (r: any) => r.phone || '—' },
      { label: '申请角色', render: (r: any) => roleLabel(r.roleType) },
      { label: '状态', render: (r: any) => applyStatusLabel(r.status) },
      { label: '申请时间', key: 'applyTime', render: renderDateTime('applyTime') },
      { label: '审核人', render: (r: any) => r.reviewer || '—' }
    ]
  },
  {
    title: '角色附加信息',
    fields: [
      {
        label: '附加信息',
        render: (r: any) => {
          const type = String(r.roleType || '').toLowerCase();
          if (type === 'store') {
            return `门店：${r.storeName ?? '—'}，地址：${r.storeAddress ?? '—'}`;
          }
          if (type === 'investor') {
            return `投资点位：${r.investLocation ?? '—'}，预算：${r.investBudget ?? '—'}`;
          }
          return `推广渠道：${r.promoteChannel ?? r.channel ?? '—'}，预期粉丝：${r.expectFans ?? '—'}`;
        }
      }
    ]
  }
];

const rowActions: RowAction[] = [
  {
    label: '详情',
    type: 'info',
    // 弹层展示：按 id 拉取申请详情并合并 extraForm，不跳转新页面
    detail: {
      title: '角色开通审核详情',
      groups: detailGroups,
      load: async (row: any) => {
        const detail: any = await fetchAdminRoleApplicationDetail(row.id);
        return detail ? { ...detail, ...parseExtra(detail.extraForm) } : detail;
      }
    }
  },
  {
    label: '通过',
    type: 'success',
    reasonPrompt: '确认通过该申请？（请填写备注）',
    handler: (row, reason) => store.reviewApplication(row.id, true, row.subjectId, row.subjectName, reason),
    visible: row => row.status === 'PENDING'
  },
  {
    label: '驳回',
    type: 'error',
    reasonPrompt: '确认驳回该申请？（请填写备注）',
    handler: (row, reason) => store.reviewApplication(row.id, false, null, null, reason),
    visible: row => row.status === 'PENDING'
  }
];

const config: AdminListConfig = {
  title: '角色开通审核',
  remoteKey: 'roleApplications',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('roleApplications', search, page, pageSize)
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
