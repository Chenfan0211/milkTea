<script setup lang="ts">
import { h } from 'vue';
import { NImage } from 'naive-ui';
import { useRoute } from 'vue-router';
import AdminDetailPage from '@/views/_shared/AdminDetailPage.vue';
import type { DetailGroup } from '@/views/_shared/AdminDetailPage.vue';
import { useAdminStore } from '@/store/modules/admin';
import { formatFen, formatDateTime } from '@/views/_shared/render';

const store = useAdminStore();
const route = useRoute();

const payStatusLabel = (v: string) =>
  ({ UNPAID: '未支付', PAID: '已支付', REFUNDED: '已退款' })[v] ?? v;
const mealTypeLabel = (v: string) =>
  ({ dinein: '堂食', DINEIN: '堂食', DINE_IN: '堂食', pickup: '自取', PICKUP: '自取', takeout: '自取', TAKEOUT: '自取' } as Record<string, string>)[v] ?? v ?? '—';
const refundStatusLabel = (v: string) =>
  ({ PENDING: '退款中', REFUNDED: '已退款' } as Record<string, string>)[v] ?? v;
const statusLabel = (v: string) =>
  ({ CREATED: '待支付', PAID: '待核销', COMPLETED: '已完成', CANCELED: '已取消' } as Record<string, string>)[v] ?? v;

/** 商品明细：图片 + 名称 x数量 + 规格 + 单价 */
function renderItems(row: any) {
  const items = Array.isArray(row.items) ? row.items : [];
  if (!items.length) return row.summary || '—';
  return h(
    'div',
    { style: 'display:flex;flex-direction:column;gap:10px;width:100%' },
    items.map((it: any) =>
      h('div', { style: 'display:flex;align-items:center;gap:10px' }, [
        it.image
          ? h(NImage, { src: it.image, width: 40, height: 40, objectFit: 'cover', style: 'border-radius:6px;flex-shrink:0' })
          : h('div', { style: 'width:40px;height:40px;border-radius:6px;background:#f0f0f0;flex-shrink:0;display:flex;align-items:center;justify-content:center;color:#9b9b96;font-size:12px' }, '无图'),
        h('div', { style: 'display:flex;flex-direction:column;gap:2px' }, [
          h('div', { style: 'font-size:14px;color:#333' }, `${it.name} x${it.quantity}`),
          h('div', { style: 'font-size:12px;color:#9b9b96' }, it.spec || ''),
          h('div', { style: 'font-size:13px;color:#666762' }, `¥${formatFen(it.unitPrice)}`)
        ])
      ])
    )
  );
}

const groups: DetailGroup[] = [
  {
    title: '基础信息',
    fields: [
      { label: '订单号', key: 'orderNo' },
      { label: '门店', key: 'store' },
      { label: '用户', key: 'user' },
      { label: '订单状态', render: (r: any) => statusLabel(r.status) },
      { label: '支付状态', render: (r: any) => payStatusLabel(r.payStatus) },
      { label: '用餐方式', render: (r: any) => mealTypeLabel(r.mealType) },
      { label: '退款状态', render: (r: any) => refundStatusLabel(r.refundStatus) },
      { label: '取餐码', render: (r: any) => r.pickupCode || '—' },
      { label: '下单时间', render: (r: any) => formatDateTime(r.createTime) },
      { label: '支付时间', render: (r: any) => formatDateTime(r.payTime) },
      { label: '核销时间', render: (r: any) => formatDateTime(r.verifyTime) },
      { label: '完成时间', render: (r: any) => formatDateTime(r.completeTime) }
    ]
  },
  {
    title: '商品明细',
    fields: [{ label: '商品', render: (r: any) => renderItems(r) }]
  },
  {
    title: '金额与分账',
    fields: [
      { label: '商品原价（元）', render: (r: any) => formatFen(r.originalAmount) },
      { label: '优惠金额（元）', render: (r: any) => `-${formatFen(r.discountAmount)}` },
      { label: '优惠券抵扣（元）', render: (r: any) => `-${formatFen(r.couponDiscount)}` },
      { label: '实付金额（元）', render: (r: any) => formatFen(r.paidAmount) },
      { label: '供应商（成本合计）', render: (r: any) => `¥${(r.split?.costTotal ?? 0).toFixed(2)}` },
      { label: '门店', render: (r: any) => `¥${(r.split?.storeShare ?? 0).toFixed(2)}` },
      { label: '资源方', render: (r: any) => `¥${(r.split?.channelShare ?? 0).toFixed(2)}` },
      { label: '投资人', render: (r: any) => `¥${(r.split?.investorShare ?? 0).toFixed(2)}` },
      { label: '平台（剩余）', render: (r: any) => `¥${(r.split?.platformShare ?? 0).toFixed(2)}` }
    ]
  }
];

async function fetchRow() {
  const orderNo = String(route.query.orderNo ?? '');
  if (!orderNo) return null;
  const cached = store.orders.find((item: any) => item.orderNo === orderNo);
  if (cached) return cached;
  return store.loadAdminOrderDetail(orderNo);
}
</script>

<template>
  <AdminDetailPage title="订单详情" back-path="/trade/order" :groups="groups" :fetch-row="fetchRow" />
</template>
