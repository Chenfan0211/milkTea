<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import AdminDetailPage from '@/views/_shared/AdminDetailPage.vue';
import type { DetailGroup } from '@/views/_shared/AdminDetailPage.vue';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();
const route = useRoute();

const resultLabel = (v: string) => ({ success: '核销成功', rejected: '重复拦截' })[v] ?? v;

const row = computed(() => store.verifies.find((item: any) => String(item.id) === String(route.query.id)) ?? null);

const groups: DetailGroup[] = [
  {
    title: '核销信息',
    fields: [
      { label: '核销码', key: 'verifyCode' },
      { label: '订单号', key: 'orderNo' },
      { label: '门店', key: 'store' },
      { label: '操作人', key: 'operator' },
      { label: '设备', key: 'device' },
      { label: '核销类型', key: 'type' },
      { label: '结果', render: (r: any) => resultLabel(r.result) },
      { label: '时间', key: 'time' }
    ]
  }
];

function fetchRow() {
  return row.value;
}
</script>

<template>
  <AdminDetailPage title="核销记录详情" back-path="/trade/verify" :groups="groups" :fetch-row="fetchRow" />
</template>
