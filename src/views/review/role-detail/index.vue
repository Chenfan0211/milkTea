<script setup lang="ts">
import { useRoute } from 'vue-router';
import AdminDetailPage from '@/views/_shared/AdminDetailPage.vue';
import type { DetailGroup } from '@/views/_shared/AdminDetailPage.vue';
import { useAdminStore } from '@/store/modules/admin';
import { fetchAdminRoleApplicationDetail } from '@/service/api/finance';
import { renderDateTime } from '@/views/_shared/render';

const store = useAdminStore();
const route = useRoute();

const roleLabel = (v: string) =>
  (({ store: '门店', investor: '投资人', resource: '资源方', channel: '资源方' }) as Record<string, string>)[
    String(v).toLowerCase()
  ] ?? v;
const statusLabel = (v: string) =>
  (({ pending: '待审核', approved: '已通过', rejected: '已驳回' }) as Record<string, string>)[
    String(v).toLowerCase()
  ] ?? v;

/** extra_form 是 JSON 字符串，解析失败时返回空对象，避免详情页抛错 */
function parseExtra(raw: any): Record<string, any> {
  if (!raw) return {};
  if (typeof raw === 'object') return raw as Record<string, any>;
  try {
    return JSON.parse(String(raw));
  } catch {
    return {};
  }
}

/**
 * 详情数据来源：优先查后端接口。
 * 原实现只读 store 内存，刷新/直接打开 URL 时会展示本地 seed 假申请单。
 */
async function fetchRow() {
  const id = Number(route.query.id);
  if (Number.isFinite(id) && id > 0) {
    try {
      const row: any = await fetchAdminRoleApplicationDetail(id);
      return row ? { ...row, ...parseExtra(row.extraForm) } : row;
    } catch {
      // 接口失败再回退内存缓存
    }
  }
  return store.roleApplications.find((item: any) => String(item.id) === String(route.query.id)) ?? null;
}

const groups: DetailGroup[] = [
  {
    title: '申请信息',
    fields: [
      { label: '申请人', render: (r: any) => r.nickName || r.name || '—' },
      { label: '用户ID', render: (r: any) => (r.userId == null ? '—' : String(r.userId)) },
      { label: '姓名', render: (r: any) => r.name || r.nickName || '—' },
      { label: '手机号', render: (r: any) => r.phone || '—' },
      { label: '申请角色', render: (r: any) => roleLabel(r.roleType) },
      { label: '状态', render: (r: any) => statusLabel(r.status) },
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
</script>

<template>
  <AdminDetailPage title="角色开通审核详情" back-path="/review/role" :groups="groups" :fetch-row="fetchRow" />
</template>
