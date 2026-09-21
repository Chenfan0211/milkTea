<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import AdminDetailPage from '@/views/_shared/AdminDetailPage.vue';
import type { DetailGroup } from '@/views/_shared/AdminDetailPage.vue';
import { useAdminStore } from '@/store/modules/admin';


const store = useAdminStore();
const route = useRoute();

const statusLabel = (v: string) => ({ valid: '有效', invalid: '已作废' })[v] ?? v;

const row = computed(() => store.snapshots.find((item: any) => String(item.id) === String(route.query.id)) ?? null);

const groups: DetailGroup[] = [
  {
    title: '基础信息',
    fields: [
      { label: '快照号', key: 'snapshotNo' },
      { label: '订单号', key: 'orderNo' },
      { label: '商品信息', key: 'summary' },
      { label: '商品件数', key: 'itemCount' },
      { label: '合计校验', key: 'totalCheck' },
      { label: '状态', render: (r: any) => statusLabel(r.status) },
      { label: '创建时间', key: 'createTime' }
    ]
  },
  {
    title: '分账金额（元）',
    fields: [
      { label: '供应商（成本）', render: (r: any) => `¥${(r.supplierAmount ?? 0).toFixed(2)}` },
      { label: '门店', render: (r: any) => `¥${(r.storeAmount ?? 0).toFixed(2)}` },
      { label: '资源方', render: (r: any) => `¥${(r.channelAmount ?? 0).toFixed(2)}` },
      { label: '投资人', render: (r: any) => `¥${(r.investorAmount ?? 0).toFixed(2)}` },
      { label: '平台分佣', render: (r: any) => `¥${(r.platformCommission ?? 0).toFixed(2)}` },
      { label: '平台提成', render: (r: any) => `¥${(r.platformBonus ?? 0).toFixed(2)}` },
      { label: '平台合计', render: (r: any) => `¥${(r.platformAmount ?? 0).toFixed(2)}` }
    ]
  }
];

function fetchRow() {
  return row.value;
}
</script>

<template>
  <AdminDetailPage title="分账快照详情" back-path="/finance/snapshot" :groups="groups" :fetch-row="fetchRow" />
</template>
