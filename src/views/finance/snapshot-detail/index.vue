<script setup lang="ts">
import { useRoute } from 'vue-router';
import AdminDetailPage from '@/views/_shared/AdminDetailPage.vue';
import type { DetailGroup } from '@/views/_shared/AdminDetailPage.vue';
import { useAdminStore } from '@/store/modules/admin';
import { fetchAdminSnapshotDetail } from '@/service/api/finance';
import { formatDateTime } from '@/views/_shared/render';

const store = useAdminStore();
const route = useRoute();

const statusLabel = (v: string) => ({ valid: '有效', invalid: '已作废' })[v] ?? v;
const totalCheckLabel = (v: string) => ({ 一致: '一致', 不一致: '不一致', ok: '一致', mismatch: '不一致' })[v] ?? v;
const money = (v: any) => `¥${(Number(v) || 0).toFixed(2)}`;

/**
 * 详情数据来源：优先查后端接口（含 order_item 聚合出的商品摘要）。
 * 原实现只读 store 内存，刷新/直接打开 URL 时会展示本地 seed 假快照。
 */
async function fetchRow() {
  const id = Number(route.query.id);
  if (Number.isFinite(id) && id > 0) {
    try {
      return await fetchAdminSnapshotDetail(id);
    } catch {
      // 接口失败再回退内存缓存，避免详情页空白
    }
  }
  return store.snapshots.find((item: any) => String(item.id) === String(route.query.id)) ?? null;
}

const groups: DetailGroup[] = [
  {
    title: '基础信息',
    fields: [
      { label: '快照号', key: 'snapshotNo' },
      { label: '订单号', key: 'orderNo' },
      { label: '商品信息', render: (r: any) => r.summary || '—' },
      { label: '商品件数', render: (r: any) => String(r.itemCount ?? 0) },
      { label: '合计校验', render: (r: any) => totalCheckLabel(r.totalCheck) },
      { label: '状态', render: (r: any) => statusLabel(r.status) },
      { label: '创建时间', render: (r: any) => formatDateTime(r.createTime) }
    ]
  },
  {
    title: '分账金额（元）',
    fields: [
      { label: '供应商（成本）', render: (r: any) => money(r.supplierAmount) },
      { label: '门店', render: (r: any) => money(r.storeAmount) },
      { label: '资源方', render: (r: any) => money(r.channelAmount) },
      { label: '投资人', render: (r: any) => money(r.investorAmount) },
      { label: '平台分佣', render: (r: any) => money(r.platformCommission) },
      { label: '平台提成', render: (r: any) => money(r.platformBonus) },
      { label: '平台合计', render: (r: any) => money(r.platformAmount) }
    ]
  }
];
</script>

<template>
  <AdminDetailPage title="分账快照详情" back-path="/finance/snapshot" :groups="groups" :fetch-row="fetchRow" />
</template>
