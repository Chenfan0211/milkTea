<script setup lang="ts">
defineOptions({
  name: 'MallAfterSale'
});

import { h, reactive, ref } from 'vue';
import { NButton, NForm, NFormItem, NInput, NModal } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import DetailModal from '@/views/_shared/DetailModal.vue';
import type { DetailGroup } from '@/views/_shared/detail-types';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import { formatDateTime, formatFen, renderTag, statusMap } from '@/views/_shared/render';
import { retryRefundApi } from '@/service/api/crud';
import { approveMallAfterSale, fetchMallAfterSales, rejectMallAfterSale } from '@/service/api/mall';
import type { MallAfterSale, MallAfterSaleItem, MallAfterSaleStatus } from '@/service/api/mall';

const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);
const detailVisible = ref(false);
const detailRow = ref<MallAfterSale | null>(null);
const detailGroups = ref<DetailGroup[]>([]);
const reviewVisible = ref(false);
const reviewSaving = ref(false);
const reviewRow = ref<MallAfterSale | null>(null);
const reviewMode = ref<'approve' | 'reject'>('approve');
const reviewForm = reactive({ remark: '' });

const afterSaleStatusMap = statusMap({
  PENDING: ['待审核', 'warning'],
  APPROVED: ['已通过', 'info'],
  REFUNDING: ['退款中', 'primary'],
  SUCCESS: ['退款成功', 'success'],
  FAILED: ['退款失败', 'error'],
  REJECTED: ['已驳回', 'default'],
  REFUNDED: ['已退款', 'success']
});

const statusOptions = [
  { label: '待审核', value: 'PENDING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '退款中', value: 'REFUNDING' },
  { label: '退款成功', value: 'SUCCESS' },
  { label: '退款失败', value: 'FAILED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已退款', value: 'REFUNDED' }
];

function itemSummary(row: MallAfterSale): string {
  const items = row.items ?? [];
  return (
    items
      .map(item => {
        const name = item.name || item.skuCode || '商品';
        return `${name} × ${Number(item.quantity) || 0}`;
      })
      .join('、') || '—'
  );
}

function renderItems(row: MallAfterSale) {
  const items = row.items ?? [];
  if (!items.length) return '—';
  return h(
    'div',
    { style: 'display:flex;flex-direction:column;gap:4px;line-height:1.7' },
    items.map(renderAfterSaleItem)
  );
}

function renderAfterSaleItem(item: MallAfterSaleItem) {
  const name = item.name || item.skuCode || '商品';
  const spec = item.spec ? ` / ${item.spec}` : '';
  const quantity = Number(item.quantity) || 0;
  const unitPrice = formatFen(item.unitPrice);
  const lineAmount = formatFen(item.lineAmount ?? (Number(item.unitPrice) || 0) * quantity);
  return h('span', `${name}${spec}：¥${unitPrice} × ${quantity}，小计 ¥${lineAmount}`);
}

function buildDetailGroups(row: MallAfterSale): DetailGroup[] {
  return [
    {
      title: '售后单信息',
      fields: [
        { label: '售后单号', key: 'afterSaleNo' },
        { label: '订单号', key: 'orderNo' },
        { label: '状态', render: () => renderTag('status', afterSaleStatusMap)(row) },
        { label: '申请用户', render: () => row.userNick || row.userId || '—' },
        { label: '退款金额（元）', render: () => `¥${formatFen(row.refundAmount)}` },
        { label: '申请时间', render: () => formatDateTime(row.applyTime) },
        { label: '审核时间', render: () => formatDateTime(row.reviewTime) },
        { label: '审核人', render: () => row.reviewer || '—' },
        { label: '完成时间', render: () => formatDateTime(row.completeTime) }
      ]
    },
    {
      title: '退款信息',
      fields: [
        { label: '退款单号', render: () => row.refundNo || '—' },
        { label: '退款状态', render: () => row.refundStatus || row.status || '—' },
        { label: '失败原因', render: () => row.refundFailReason || '—' }
      ]
    },
    {
      title: '售后原因',
      fields: [{ label: '原因', render: () => row.reason || '—' }]
    },
    {
      title: '退货商品',
      fields: [{ label: '商品明细', render: () => renderItems(row) }]
    }
  ];
}

function openDetail(row: MallAfterSale) {
  detailRow.value = row;
  detailGroups.value = buildDetailGroups(row);
  detailVisible.value = true;
}

function openReview(row: MallAfterSale, mode: 'approve' | 'reject') {
  reviewRow.value = row;
  reviewMode.value = mode;
  reviewForm.remark = '';
  reviewVisible.value = true;
}

async function retryAfterSaleRefund(row: MallAfterSale) {
  if (!row.refundId) {
    window.$message?.warning('未找到关联退款单，请检查售后数据');
    return;
  }
  await retryRefundApi(row.refundId);
  window.$message?.success('已重新发起退款');
  await listRef.value?.reload();
}

async function submitReview() {
  const row = reviewRow.value;
  if (!row || reviewSaving.value) return;
  const remark = reviewForm.remark.trim();
  if (reviewMode.value === 'reject' && !remark) {
    window.$message?.warning('请填写驳回原因');
    return;
  }
  reviewSaving.value = true;
  try {
    if (reviewMode.value === 'approve') {
      await approveMallAfterSale(row.afterSaleNo, { remark });
      window.$message?.success('售后申请已通过，退款处理中');
    } else {
      await rejectMallAfterSale(row.afterSaleNo, { remark });
      window.$message?.success('售后申请已驳回');
    }
    reviewVisible.value = false;
    await listRef.value?.reload();
  } finally {
    reviewSaving.value = false;
  }
}

const columns: DataTableColumns<MallAfterSale> = [
  { title: '售后单号', key: 'afterSaleNo', width: 180 },
  { title: '订单号', key: 'orderNo', width: 175 },
  { title: '用户', key: 'userNick', width: 120, render: (row: MallAfterSale) => row.userNick || row.userId || '—' },
  { title: '退货商品', key: 'items', minWidth: 200, render: (row: MallAfterSale) => itemSummary(row) },
  {
    title: '退款金额(元)',
    key: 'refundAmount',
    width: 120,
    align: 'right',
    render: (row: MallAfterSale) => `¥${formatFen(row.refundAmount)}`
  },
  { title: '状态', key: 'status', width: 100, render: renderTag('status', afterSaleStatusMap) },
  { title: '退款单号', key: 'refundNo', width: 170, render: (row: MallAfterSale) => row.refundNo || '—' },
  { title: '申请时间', key: 'applyTime', width: 170, render: (row: MallAfterSale) => formatDateTime(row.applyTime) },
  { title: '审核人', key: 'reviewer', width: 110, render: (row: MallAfterSale) => row.reviewer || '—' }
];

const searchFields: SearchField[] = [
  { key: 'search', label: '售后单', placeholder: '售后单号 / 订单号' },
  { key: 'status', label: '状态', type: 'select', options: statusOptions }
];

const config: AdminListConfig = {
  title: '售后审核',
  columns,
  searchFields,
  refreshOnEnter: true,
  rowActions: [
    {
      label: '详情',
      type: 'info',
      handler: (row: MallAfterSale) => openDetail(row)
    },
    {
      label: '重试退款',
      type: 'warning',
      confirm: '确认重试该售后退款？将复用原退款单，不会重复退款。',
      handler: (row: MallAfterSale) => retryAfterSaleRefund(row),
      visible: (row: MallAfterSale) => row.status === 'FAILED'
    },
    {
      label: '通过',
      type: 'primary',
      handler: (row: MallAfterSale) => openReview(row, 'approve'),
      visible: (row: MallAfterSale) => row.status === 'PENDING'
    },
    {
      label: '驳回',
      type: 'error',
      handler: (row: MallAfterSale) => openReview(row, 'reject'),
      visible: (row: MallAfterSale) => row.status === 'PENDING'
    }
  ],
  loadData: async ({ page, pageSize, search }) => {
    const result = await fetchMallAfterSales({
      current: page,
      size: pageSize,
      status: (search?.status || undefined) as MallAfterSaleStatus | undefined,
      search: String(search?.search ?? '').trim() || undefined
    });
    return {
      data: result.records ?? [],
      total: Number(result.total) || 0
    };
  }
};
</script>

<template>
  <div class="mall-after-sale-page">
    <AdminListPage ref="listRef" :config="config" />

    <DetailModal v-model:show="detailVisible" title="售后单详情" :groups="detailGroups" :row="detailRow" />

    <NModal
      v-model:show="reviewVisible"
      preset="card"
      :title="reviewMode === 'approve' ? '审核通过' : '驳回售后'"
      class="w-520px"
    >
      <div v-if="reviewRow" class="review-summary">
        <div class="review-summary__row">
          <span>售后单号</span>
          <strong>{{ reviewRow.afterSaleNo }}</strong>
        </div>
        <div class="review-summary__row">
          <span>订单号</span>
          <strong>{{ reviewRow.orderNo }}</strong>
        </div>
        <div class="review-summary__row">
          <span>退款金额</span>
          <strong>¥{{ formatFen(reviewRow.refundAmount) }}</strong>
        </div>
        <div class="review-summary__row">
          <span>退货商品</span>
          <strong>{{ itemSummary(reviewRow) }}</strong>
        </div>
      </div>
      <NForm label-placement="top">
        <NFormItem
          :label="reviewMode === 'approve' ? '审核备注（选填）' : '驳回原因'"
          :required="reviewMode === 'reject'"
        >
          <NInput
            v-model:value="reviewForm.remark"
            type="textarea"
            :maxlength="200"
            show-count
            :placeholder="reviewMode === 'approve' ? '可填写审核说明' : '请填写驳回原因，将同步给用户'"
          />
        </NFormItem>
      </NForm>
      <div class="review-hint">
        {{
          reviewMode === 'approve'
            ? '通过后将立即按服务端计算的售后金额发起原路退款，请先核对退货商品与金额。'
            : '驳回后用户可重新申请售后。'
        }}
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton :disabled="reviewSaving" @click="reviewVisible = false">取消</NButton>
          <NButton type="primary" :loading="reviewSaving" @click="submitReview">
            {{ reviewMode === 'approve' ? '确认通过并退款' : '确认驳回' }}
          </NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.mall-after-sale-page {
  min-width: 0;
}
.review-summary {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 16px;
  padding: 12px;
  border-radius: 8px;
  background: #f7f8f5;
}
.review-summary__row {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: #9b9b96;
}
.review-summary__row strong {
  color: #333;
  font-weight: 600;
  word-break: break-all;
}
.review-hint {
  margin-top: 8px;
  color: #9b9b96;
  font-size: 12px;
  line-height: 1.6;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
