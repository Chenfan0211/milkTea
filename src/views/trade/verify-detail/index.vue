<script setup lang="ts">
import { ref } from 'vue';
import { useRoute } from 'vue-router';
import AdminDetailPage from '@/views/_shared/AdminDetailPage.vue';
import type { DetailGroup } from '@/views/_shared/AdminDetailPage.vue';
import { useAdminStore } from '@/store/modules/admin';
import { fetchAdminVerifyDetail } from '@/service/api/trade';
import { formatDateTime } from '@/views/_shared/render';

const store = useAdminStore();
const route = useRoute();

const resultLabel = (v: string) =>
  (
    ({
      SUCCESS: '核销成功',
      success: '核销成功',
      FAIL: '核销失败',
      failed: '核销失败',
      rejected: '重复拦截'
    }) as Record<string, string>
  )[v] ?? v;
const typeLabel = (v: string) => ({ ORDER: '订单', EXCHANGE: '兑换' })[v] ?? v;

/**
 * 详情数据来源：优先查后端接口（始终拿数据库最新值）。
 *
 * 原实现只从 store 内存 find()，不发起任何请求：刷新页面或直接打开详情 URL 时
 * store 里只有 localStorage 缓存的 seed 假数据，页面会展示不存在的核销记录。
 * 这里改为接口优先，接口异常时才回退内存缓存（离线/接口未接入场景）。
 */
async function fetchRow() {
  const id = Number(route.query.id);
  if (Number.isFinite(id) && id > 0) {
    try {
      return await fetchAdminVerifyDetail(id);
    } catch {
      // 接口失败再回退，避免详情页完全空白
    }
  }
  return store.verifies.find((item: any) => String(item.id) === String(route.query.id)) ?? null;
}

const groups: DetailGroup[] = [
  {
    title: '核销信息',
    fields: [
      { label: '核销码', key: 'verifyCode' },
      { label: '订单号', key: 'orderNo' },
      { label: '门店', render: (r: any) => r.store || '—' },
      { label: '操作人', render: (r: any) => r.operator || '—' },
      { label: '设备', render: (r: any) => r.device || '—' },
      { label: '核销类型', render: (r: any) => typeLabel(r.type) },
      { label: '结果', render: (r: any) => resultLabel(r.result) },
      { label: '时间', render: (r: any) => formatDateTime(r.time ?? r.createTime) }
    ]
  }
];
</script>

<template>
  <AdminDetailPage title="核销记录详情" back-path="/trade/verify" :groups="groups" :fetch-row="fetchRow" />
</template>
