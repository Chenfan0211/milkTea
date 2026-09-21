<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import AdminDetailPage from '@/views/_shared/AdminDetailPage.vue';
import type { DetailGroup } from '@/views/_shared/AdminDetailPage.vue';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();
const route = useRoute();

const roleLabel = (v: string) => ({ store: '门店', investor: '投资人', resource: '资源方' })[v] ?? v;
const statusLabel = (v: string) => ({ pending: '待审核', approved: '已通过', rejected: '已驳回' })[v] ?? v;

const row = computed(() => store.roleApplications.find((item: any) => String(item.id) === String(route.query.id)) ?? null);

const groups: DetailGroup[] = [
  {
    title: '申请信息',
    fields: [
      { label: '申请人', key: 'nickName' },
      { label: '用户ID', key: 'userId' },
      { label: '姓名', key: 'name' },
      { label: '手机号', key: 'phone' },
      { label: '申请角色', render: (r: any) => roleLabel(r.roleType) },
      { label: '状态', render: (r: any) => statusLabel(r.status) },
      { label: '申请时间', key: 'applyTime' },
      { label: '审核人', render: (r: any) => r.reviewer || '—' }
    ]
  },
  {
    title: '角色附加信息',
    fields: [
      {
        label: '附加信息',
        render: (r: any) =>
          r.roleType === 'store'
            ? `门店：${r.storeName ?? '—'}，地址：${r.storeAddress ?? '—'}`
            : r.roleType === 'investor'
              ? `投资点位：${r.investLocation ?? '—'}，预算：${r.investBudget ?? '—'}`
              : `推广渠道：${r.promoteChannel ?? '—'}，预期粉丝：${r.expectFans ?? '—'}`
      }
    ]
  }
];

function fetchRow() {
  return row.value;
}
</script>

<template>
  <AdminDetailPage title="角色开通审核详情" back-path="/review/role" :groups="groups" :fetch-row="fetchRow" />
</template>
