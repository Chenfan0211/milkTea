<script setup lang="ts">

defineOptions({
  name: 'trade_order'
});

import { computed, h, onMounted, ref } from 'vue';
import { NImage } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DetailGroup } from '@/views/_shared/detail-types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { fetchSubjectStores } from '@/service/api/subject';
import { renderTag, statusMap, renderMoney, renderDateTime, formatFen, formatDateTime } from '@/views/_shared/render';

const store = useAdminStore();

// 门店下拉：可搜索下拉框的数据源（后台门店列表）
const storeOptions = ref<{ label: string; value: string }[]>([]);

async function loadStoreOptions() {
  try {
    const res: any = await fetchSubjectStores({ current: 1, size: 500 });
    const records = res?.records ?? [];
    storeOptions.value = records.map((s: any) => ({
      label: String(s.name ?? s.storeName ?? `门店${s.id}`),
      value: String(s.id)
    }));
  } catch {
    // 门店列表拉取失败不阻塞列表：下拉保持为空，用户仍可按订单号/状态查询
    storeOptions.value = [];
  }
}

onMounted(loadStoreOptions);

// 分账明细弹窗
const splitVisible = ref(false);
const splitRow = ref<any>(null);

function openSplit(row: any) {
  splitRow.value = row;
  splitVisible.value = true;
}

/**
 * 分账明细展示口径（«固定金额 + 成本直给» 模型）。
 *
 * 后端 SplitCalculator 的计算顺序：
 *   门店份额   = 门店每件提成 × 件数
 *   资源方份额 = 有渠道归因 ? 资源方每件提成 × 件数 : 0
 *   成本合计   = Σ(商品成本价 × 该商品件数)
 *   平台提成   = Σ(商品平台提成 × 该商品件数)
 *   投资人基础 = 实付 − 门店 − 资源方 − 成本合计 − 平台提成
 *   投资人份额 = max(0, 投资人基础) × 投资人比例
 *   平台剩余   = 实付 − 门店 − 资源方 − 成本合计 − 投资人份额
 *
 * 关键点：成本价与平台提成都是「单价（分/件）」，必须先乘以件数再参与
 * 加减；平台剩余里已经扣掉了「成本合计」与「平台提成」，不需要再减一次。
 *
 * 后端分账快照（split_snapshot）目前只在核销后生成，未核销订单没有快照，
 * 因此这里按上面的口径本地复算，保证「分账明细」在任何状态下都能给出
 * 与后端一致的数字，而不是静默显示 ¥0.00。
 */

/** 明细件数：缺失/非正数按 1 件兜底（与后端 quantityOf 一致） */
function lineQuantity(quantity: any): number {
  const n = Number(quantity);
  return Number.isFinite(n) && n > 0 ? n : 1;
}

/** 分转元展示（金额一律按「分」参与计算，仅展示时转元） */
function yuan(fen: any): string {
  const n = Number(fen);
  return (Number.isFinite(n) ? n / 100 : 0).toFixed(2);
}

/**
 * 分账明细（单位：分）。
 *
 * 优先使用后端下发的完整快照（五种份额齐全 + 实付非 0）；快照缺失或
 * 不完整时（未核销订单没有 split_snapshot）本地按同一口径复算。
 */
const splitDetail = computed(() => {
  const row: any = splitRow.value;
  if (!row) return null;

  const items: any[] = Array.isArray(row.items) ? row.items : [];
  const paid = Number(row.paidAmount) || 0;
  const split = row.split || {};

  // 明细派生：每个商品成本单价/提成单价 × 件数
  const itemCount = items.length
    ? items.reduce((sum, it) => sum + lineQuantity(it?.quantity), 0)
    : Number(split.itemCount) || 0;
  const costTotal = items.length
    ? items.reduce((sum, it) => sum + (Number(it?.costPrice) || 0) * lineQuantity(it?.quantity), 0)
    : (Number(split.costTotal) || 0) * 100;
  const commission = items.length
    ? items.reduce((sum, it) => sum + (Number(it?.platformCommission) || 0) * lineQuantity(it?.quantity), 0)
    : (Number(split.platformCommission) || 0) * 100;

  // 快照完整时直接采信后端结果（避免前后端各算一套导致对不上）
  const snapshotComplete =
    paid > 0 &&
    split.storeShare != null &&
    split.channelShare != null &&
    split.investorShare != null &&
    split.platformShare != null;

  if (snapshotComplete) {
    return {
      paid,
      itemCount: Number(split.itemCount) || itemCount,
      costTotal: Number(split.costTotal) || costTotal,
      storeShare: Number(split.storeShare) || 0,
      channelShare: Number(split.channelShare) || 0,
      investorShare: Number(split.investorShare) || 0,
      commission: Number(split.platformCommission) || 0,
      platformShare: Number(split.platformShare) || 0,
      base: Number(split.base) || 0,
      fromSnapshot: true
    };
  }

  // 本地复算：规则取启用中的全局分账规则（store_ratio / channel_ratio 为每件提成，单位分）
  const enabledRule = store.splitRules.find(
    (r: any) => String(r?.scope || '').toUpperCase() === 'GLOBAL' && r?.status === 'enabled'
  );
  const storePerItem = Number(enabledRule?.storeRatio) || 0;
  const channelPerItem = Number(enabledRule?.channelRatio) || 0;
  const investorRatio = Number(enabledRule?.investorRatio) || 0;

  const storeShare = storePerItem * itemCount;
  const channelShare = row.channelSubjectId == null ? 0 : channelPerItem * itemCount;
  const base = paid - storeShare - channelShare - costTotal - commission;
  const investorShare = base > 0 ? Math.round((base * investorRatio) / 10000) : 0;
  const platformShare = paid - storeShare - channelShare - costTotal - investorShare;

  return {
    paid,
    itemCount,
    costTotal,
    storeShare,
    channelShare,
    investorShare,
    commission,
    platformShare,
    base,
    fromSnapshot: false
  };
});

const columns: DataTableColumns<any> = [
  { title: '订单号', key: 'orderNo', width: 160 },
  { title: '门店', key: 'store', width: 130 },
  { title: '用户', key: 'user', width: 110 },
  { title: '商品摘要', key: 'summary', minWidth: 160 },
  {
    title: '支付状态',
    key: 'payStatus',
    width: 100,
    render: renderTag('payStatus', statusMap({ UNPAID: ['未支付', 'info'], PAID: ['已支付', 'primary'], REFUNDED: ['已退款', 'default'] }))
  },
  {
    title: '用餐方式',
    key: 'mealType',
    width: 90,
    render: (row: any) => ({ dinein: '堂食', DINEIN: '堂食', DINE_IN: '堂食', pickup: '自取', PICKUP: '自取', takeout: '自取', TAKEOUT: '自取' } as Record<string, string>)[row.mealType] ?? row.mealType ?? '—'
  },
  { title: '实付金额(元)', key: 'paidAmount', width: 120, align: 'right', render: renderMoney('paidAmount') },
  {
    title: '订单状态',
    key: 'status',
    width: 120,
    render: renderTag(
      'status',
      statusMap({
        CREATED: ['待支付', 'info'],
        PAID: ['待核销', 'primary'],
        COMPLETED: ['已完成', 'success'],
        CANCELED: ['已取消', 'default']
      })
    )
  },
  {
    title: '取餐码',
    key: 'pickupCode',
    width: 100,
    // 业务口径：核销码只在「已支付待核销」阶段有效
    // （待支付未生成、已核销/已完成已失效、已取消/已退款不展示）
    render: (row: any) => (row.status === 'PAID' ? row.pickupCode || '—' : '—')
  },
  { title: '创建时间', key: 'createTime', width: 170, render: renderDateTime('createTime') }
];

const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  {
    // 门店改为可搜索下拉框：按门店主体 id 精确过滤（后端 storeSubjectId 参数）
    key: 'storeSubjectId',
    label: '门店',
    type: 'select',
    placeholder: '请选择门店',
    options: () => storeOptions.value
  },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待支付', value: 'CREATED' },
      { label: '待核销', value: 'PAID' },
      { label: '已完成', value: 'COMPLETED' },
      { label: '已取消', value: 'CANCELED' }
    ]
  }
];

// ---------- 订单详情弹层字段（原 /trade/order-detail 页面口径，改为弹层展示） ----------

const payStatusLabel = (v: string) => ({ UNPAID: '未支付', PAID: '已支付', REFUNDED: '已退款' })[v] ?? v;
const mealTypeLabel = (v: string) =>
  ({
    dinein: '堂食', DINEIN: '堂食', DINE_IN: '堂食',
    pickup: '自取', PICKUP: '自取', takeout: '自取', TAKEOUT: '自取'
  } as Record<string, string>)[v] ?? v ?? '—';
const refundStatusLabel = (v: string) => ({ PENDING: '退款中', REFUNDED: '已退款' } as Record<string, string>)[v] ?? v;
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

const detailGroups: DetailGroup[] = [
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
      // 与列表口径一致：核销码仅已支付待核销订单展示
      { label: '取餐码', render: (r: any) => (r.status === 'PAID' ? r.pickupCode || '—' : '—') },
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

const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  { label: '分账明细', type: 'info', handler: (row: any) => openSplit(row) },
  {
    label: '详情',
    type: 'info',
    // 弹层展示：拉取含商品明细的详情接口，不跳转新页面
    detail: {
      title: '订单详情',
      groups: detailGroups,
      load: (row: any) => store.loadAdminOrderDetail(row.orderNo)
    }
  },
  {
    label: '取消订单',
    type: 'warning',
    reasonPrompt: '取消后资金原路退回，确认取消该订单？（请填写备注）',
    handler: (row, reason) => store.refundOrder(row.id, reason),
    // 业务口径：只有「已支付待核销」的订单允许取消
    visible: row => row.status === 'PAID'
  }
];
const config: AdminListConfig = {
  title: '订单管理',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) =>
    store.loadAdminOrders({
      current: page,
      size: pageSize,
      search: search?.orderNo,
      status: search?.status,
      storeSubjectId: search?.storeSubjectId
    })
};
</script>

<template>
  <div class="page-root">

  <AdminListPage :config="config" />

  <NModal v-model:show="splitVisible" preset="card" title="分账明细" class="w-560px">
    <div v-if="splitRow && splitDetail" class="split-modal">
      <div class="split-order-no">订单号：{{ splitRow.orderNo }}</div>
      <div class="split-line">
        <span>实付金额</span>
        <span>¥{{ yuan(splitDetail.paid) }}</span>
      </div>
      <div class="split-line">
        <span>供应商（成本合计 = 成本单价×件数）</span>
        <span>¥{{ yuan(splitDetail.costTotal) }}</span>
      </div>
      <div class="split-line">
        <span>门店（每件×{{ splitDetail.itemCount }}件）</span>
        <span>¥{{ yuan(splitDetail.storeShare) }}</span>
      </div>
      <div class="split-line">
        <span>资源方（每件×{{ splitDetail.itemCount }}件）</span>
        <span>¥{{ yuan(splitDetail.channelShare) }}</span>
      </div>
      <div class="split-line">
        <span>平台提成（每件×{{ splitDetail.itemCount }}件，已从平台剩余中扣除）</span>
        <span>-¥{{ yuan(splitDetail.commission) }}</span>
      </div>
      <div class="split-line">
        <span>投资人（{{ splitDetail.base > 0 ? '基础×比例%' : '基础为负' }}）</span>
        <span>¥{{ yuan(splitDetail.investorShare) }}</span>
      </div>
      <div class="split-line split-line--total">
        <span>平台（剩余）</span>
        <span>¥{{ yuan(splitDetail.platformShare) }}</span>
      </div>
      <div class="mt-16px text-12px color-#9B9B96">
        公式：平台剩余 = 实付 − 成本合计(成本单价×件数) − 门店(每件×件数) − 资源方(每件×件数) − 投资人(基础×比例%)
      </div>
      <div v-if="!splitDetail.fromSnapshot" class="mt-4px text-12px color-#9B9B96">
        该订单尚未核销，暂无分账快照，以上为按当前分账规则试算的结果。
      </div>
    </div>
  </NModal>
  </div>
</template>

<style scoped>
.split-modal {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.split-order-no {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  margin-bottom: 8px;
}
.split-line {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
  font-size: 14px;
}
.split-line--total {
  font-weight: 600;
  border-bottom: none;
}
</style>

