<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import AdminDetailPage from '@/views/_shared/AdminDetailPage.vue';
import type { DetailGroup } from '@/views/_shared/AdminDetailPage.vue';
import { useAdminStore } from '@/store/modules/admin';
import { formatFen } from '@/views/_shared/render';

const store = useAdminStore();
const route = useRoute();

const statusLabel = (v: string) =>
  ({ CREATED: '待支付', PAID: '已支付', VERIFIED: '已核销', COMPLETED: '已完成', REFUNDED: '已退款' })[v] ?? v;

const row = computed(() => store.orders.find((item: any) => String(item.id) === String(route.query.id)) ?? null);

const groups: DetailGroup[] = [
  {
    title: '基础信息',
    fields: [
      { label: '订单号', key: 'orderNo' },
      { label: '门店', key: 'store' },
      { label: '用户', key: 'user' },
      { label: '订单状态', render: (r: any) => statusLabel(r.status) },
      { label: '取餐码', render: (r: any) => r.pickupCode || '—' },
      { label: '创建时间', key: 'createTime' }
    ]
  },
  { title: '商品信息', fields: [{ label: '商品摘要', key: 'summary' }] },
  {
    title: '金额与分账',
    fields: [
      { label: '实付金额（元）', render: (r: any) => formatFen(r.paidAmount) },
      { label: '供应商（成本合计）', render: (r: any) => `¥${(r.split?.costTotal ?? 0).toFixed(2)}` },
      { label: '门店', render: (r: any) => `¥${(r.split?.storeShare ?? 0).toFixed(2)}` },
      { label: '资源方', render: (r: any) => `¥${(r.split?.channelShare ?? 0).toFixed(2)}` },
      { label: '投资人', render: (r: any) => `¥${(r.split?.investorShare ?? 0).toFixed(2)}` },
      { label: '平台（剩余）', render: (r: any) => `¥${(r.split?.platformShare ?? 0).toFixed(2)}` }
    ]
  }
];

function fetchRow() {
  return row.value;
}
</script>

<template>
  <AdminDetailPage title="订单详情" back-path="/trade/order" :groups="groups" :fetch-row="fetchRow" />
</template>
