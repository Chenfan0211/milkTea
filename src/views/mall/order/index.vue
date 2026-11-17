<script setup lang="ts">
defineOptions({
  name: 'MallOrder'
});

import { reactive, ref } from 'vue';
import { NButton, NForm, NFormItem, NInput, NModal } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import DetailModal from '@/views/_shared/DetailModal.vue';
import type { DetailGroup } from '@/views/_shared/detail-types';
import type { AdminListConfig, SearchField } from '@/views/_shared/types';
import {
  formatDateTime,
  formatFen,
  payChannelLabel,
  renderPayChannel,
  renderTag,
  statusMap,
  toTimeRange
} from '@/views/_shared/render';
import { fetchMallOrderDetail, fetchMallOrders, shipMallOrder } from '@/service/api/mall';
import type { MallOrder, MallOrderItem, MallOrderShipping } from '@/service/api/mall';
import { describeMallSplit } from './split-detail';

const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);
const detailVisible = ref(false);
const detailRow = ref<MallOrder | null>(null);
const detailGroups = ref<DetailGroup[]>([]);
const splitVisible = ref(false);
const splitRow = ref<MallOrder | null>(null);

const shippingVisible = ref(false);
const shippingSaving = ref(false);
const shippingRow = ref<MallOrder | null>(null);
const shippingForm = reactive({
  courierCompany: '',
  trackingNo: ''
});

const orderStatusMap = statusMap({
  CREATED: ['待支付', 'warning'],
  PAID: ['待发货', 'info'],
  SHIPPED: ['待收货', 'primary'],
  COMPLETED: ['已完成', 'success'],
  CANCELED: ['已取消', 'default'],
  REFUNDED: ['已退款', 'success']
});
const statusOptions = [
  { label: '待支付', value: 'CREATED' },
  { label: '待发货', value: 'PAID' },
  { label: '待收货', value: 'SHIPPED' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '已取消', value: 'CANCELED' },
  { label: '已退款', value: 'REFUNDED' }
];

function recipient(row: MallOrder): MallOrderShipping {
  return row.shipping ?? {};
}

function fullAddress(shipping: MallOrderShipping): string {
  const region = [shipping.provinceName, shipping.cityName, shipping.districtName].filter(Boolean).join(' ');
  return [region, shipping.detailAddress].filter(Boolean).join(' ') || '—';
}

function itemSummary(row: MallOrder): string {
  const names = (row.items ?? []).map(item => item.name).filter(Boolean);
  return names.join('、') || row.summary || '—';
}

function renderItem(item: MallOrderItem): string {
  const name = item.name || '商品';
  const spec = item.spec ? ` / ${item.spec}` : '';
  const quantity = Number(item.quantity) || 0;
  const unitPrice = formatFen(item.unitPrice);
  const subtotal = formatFen(item.subTotal ?? (Number(item.unitPrice) || 0) * quantity);
  return `${name}${spec}：¥${unitPrice} × ${quantity}，小计 ¥${subtotal}`;
}

function splitMoney(value: number | null): string {
  return value == null ? '—' : `¥${formatFen(value)}`;
}

function commissionItems(row: MallOrder): string {
  const items = row.items ?? [];
  if (!items.length) return '—';
  return items
    .map(item => {
      const qty = Number(item.quantity) || 0;
      const unit = Number(item.distributionCommission) || 0;
      return `${item.name || '商品'}${item.spec ? `（${item.spec}）` : ''}：¥${formatFen(unit)} × ${qty} = ¥${formatFen(unit * qty)}`;
    })
    .join('；');
}

const splitGroups: DetailGroup[] = [
  {
    title: '团长归属',
    fields: [
      { label: '归属口径', render: (row: MallOrder) => describeMallSplit(row).attribution },
      { label: '团长用户 ID', render: (row: MallOrder) => describeMallSplit(row).leaderUserId },
      { label: '团长主体 ID', render: (row: MallOrder) => describeMallSplit(row).leaderSubjectId },
      { label: '团长名称', render: (row: MallOrder) => row.leaderName || '—' },
      { label: '分账状态', render: (row: MallOrder) => describeMallSplit(row).state },
      { label: '分账快照号', render: (row: MallOrder) => row.split?.snapshotNo || '—' },
      { label: '实付金额', render: (row: MallOrder) => splitMoney(row.paidAmount ?? null) },
      { label: '商品分销佣金（单价 × 件数）', render: (row: MallOrder) => commissionItems(row) }
    ]
  },
  {
    title: '原分账（元）',
    fields: [
      { label: '平台', render: (row: MallOrder) => splitMoney(describeMallSplit(row).platform.original) },
      { label: '供应商', render: (row: MallOrder) => splitMoney(describeMallSplit(row).supplier.original) },
      { label: '团长', render: (row: MallOrder) => splitMoney(describeMallSplit(row).leader.original) },
      {
        label: '历史其他分账',
        render: (row: MallOrder) => (row.split?.snapshotNo ? splitMoney(describeMallSplit(row).legacy) : '—')
      },
      {
        label: '合计校验',
        render: (row: MallOrder) => {
          const check = describeMallSplit(row).totalCheck;
          return check == null ? '—' : check ? '一致' : '不一致，请核对账务';
        }
      }
    ]
  },
  {
    title: '退款冲正（元）',
    fields: [
      {
        label: '平台',
        render: (row: MallOrder) => (row.split?.snapshotNo ? splitMoney(describeMallSplit(row).platform.refunded) : '—')
      },
      {
        label: '供应商',
        render: (row: MallOrder) => (row.split?.snapshotNo ? splitMoney(describeMallSplit(row).supplier.refunded) : '—')
      },
      {
        label: '团长',
        render: (row: MallOrder) => (row.split?.snapshotNo ? splitMoney(describeMallSplit(row).leader.refunded) : '—')
      }
    ]
  },
  {
    title: '当前净额（元）',
    fields: [
      {
        label: '平台',
        render: (row: MallOrder) => (row.split?.snapshotNo ? splitMoney(describeMallSplit(row).platform.net) : '—')
      },
      {
        label: '供应商',
        render: (row: MallOrder) => (row.split?.snapshotNo ? splitMoney(describeMallSplit(row).supplier.net) : '—')
      },
      {
        label: '团长',
        render: (row: MallOrder) => (row.split?.snapshotNo ? splitMoney(describeMallSplit(row).leader.net) : '—')
      }
    ]
  }
];

function buildDetailGroups(): DetailGroup[] {
  return [
    {
      title: '订单信息',
      fields: [
        { label: '订单号', key: 'orderNo' },
        { label: '订单状态', render: (row: MallOrder) => renderTag('status', orderStatusMap)(row) },
        { label: '下单用户', key: 'user' },
        { label: '支付方式', render: (row: MallOrder) => payChannelLabel(row.payChannel) },
        { label: '退款状态', render: (row: MallOrder) => row.refundStatus || '无退款' },
        {
          label: '售后状态',
          render: (row: MallOrder) => row.afterSaleStatus || (row.activeAfterSale ? '处理中' : '无售后')
        },
        { label: '售后单号', render: (row: MallOrder) => row.afterSaleNo || '—' },
        { label: '下单时间', render: (row: MallOrder) => formatDateTime(row.createTime) },
        { label: '支付时间', render: (row: MallOrder) => formatDateTime(row.payTime) },
        { label: '发货时间', render: (row: MallOrder) => formatDateTime(row.shippedTime) },
        { label: '自动完成时间', render: (row: MallOrder) => formatDateTime(row.autoCompleteTime) },
        { label: '完成时间', render: (row: MallOrder) => formatDateTime(row.completeTime) }
      ]
    },
    {
      title: '收货信息',
      fields: [
        { label: '收货人', render: (row: MallOrder) => recipient(row).recipientName || '—' },
        { label: '联系电话', render: (row: MallOrder) => recipient(row).phone || '—' },
        { label: '收货地址', render: (row: MallOrder) => fullAddress(recipient(row)) }
      ]
    },
    {
      title: '物流信息',
      fields: [
        { label: '快递公司', render: (row: MallOrder) => recipient(row).courierCompany || '待发货' },
        { label: '快递单号', render: (row: MallOrder) => recipient(row).trackingNo || '待发货' }
      ]
    },
    {
      title: '商品明细',
      fields: [{ label: '商品', render: (row: MallOrder) => (row.items ?? []).map(renderItem).join('；') || '—' }]
    },
    {
      title: '金额信息（元）',
      fields: [
        { label: '商品原价', render: (row: MallOrder) => `¥${formatFen(row.originalAmount)}` },
        { label: '优惠金额', render: (row: MallOrder) => `¥${formatFen(row.discountAmount)}` },
        { label: '优惠券抵扣', render: (row: MallOrder) => `¥${formatFen(row.couponDiscount)}` },
        { label: '实付金额', render: (row: MallOrder) => `¥${formatFen(row.paidAmount)}` }
      ]
    },
    ...splitGroups
  ];
}

function openDetail(row: MallOrder) {
  detailRow.value = row;
  detailGroups.value = buildDetailGroups();
  detailVisible.value = true;
}

function openSplit(row: MallOrder) {
  splitRow.value = row;
  splitVisible.value = true;
}

async function loadFreshDetail(row: MallOrder): Promise<MallOrder | null> {
  try {
    return await fetchMallOrderDetail(row.orderNo);
  } catch (error: any) {
    window.$message?.error(error?.message || '订单详情加载失败，请重试');
    return null;
  }
}

function openShipping(row: MallOrder) {
  if (row.refundStatus === 'PENDING') {
    window.$message?.warning('退款处理中，暂不能发货');
    return;
  }
  shippingRow.value = row;
  shippingForm.courierCompany = '';
  shippingForm.trackingNo = '';
  shippingVisible.value = true;
}

async function submitShipping() {
  if (!shippingRow.value) return;
  const courierCompany = shippingForm.courierCompany.trim();
  const trackingNo = shippingForm.trackingNo.trim();
  if (!courierCompany) {
    window.$message?.warning('请填写快递公司');
    return;
  }
  if (!trackingNo) {
    window.$message?.warning('请填写快递单号');
    return;
  }

  shippingSaving.value = true;
  try {
    await shipMallOrder(shippingRow.value.orderNo, { courierCompany, trackingNo });
    window.$message?.success('订单已发货');
    shippingVisible.value = false;
    detailVisible.value = false;
    listRef.value?.reload();
  } catch (error: any) {
    window.$message?.error(error?.message || '订单发货失败');
  } finally {
    shippingSaving.value = false;
  }
}

const columns: DataTableColumns<any> = [
  { title: '订单号', key: 'orderNo', width: 175 },
  { title: '用户', key: 'user', width: 120, render: (row: any) => row.user || '—' },
  { title: '商品', key: 'summary', minWidth: 190, render: (row: MallOrder) => itemSummary(row) },
  {
    title: '实付(元)',
    key: 'paidAmount',
    width: 105,
    align: 'right',
    render: (row: MallOrder) => `¥${formatFen(row.paidAmount)}`
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag('status', orderStatusMap)
  },
  { title: '支付方式', key: 'payChannel', width: 105, render: renderPayChannel('payChannel') },
  {
    title: '收货人',
    key: 'recipientName',
    width: 105,
    render: (row: MallOrder) => recipient(row).recipientName || '—'
  },
  {
    title: '物流单号',
    key: 'trackingNo',
    width: 160,
    render: (row: MallOrder) => recipient(row).trackingNo || '待发货'
  },
  { title: '下单时间', key: 'createTime', width: 170, render: (row: any) => formatDateTime(row.createTime) },
  { title: '发货时间', key: 'shippedTime', width: 170, render: (row: any) => formatDateTime(row.shippedTime) }
];

const searchFields: SearchField[] = [
  { key: 'search', label: '订单', placeholder: '订单号' },
  { key: 'status', label: '状态', type: 'select', options: statusOptions },
  { key: 'createTime', label: '下单时间', type: 'daterange' }
];

const config: AdminListConfig = {
  title: '商城订单',
  columns,
  searchFields,
  refreshOnEnter: true,
  rowActions: [
    {
      label: '详情',
      type: 'info',
      handler: (row: MallOrder) => openDetail(row)
    },
    {
      label: '分账明细',
      type: 'info',
      handler: (row: MallOrder) => openSplit(row)
    },
    {
      label: '发货',
      type: 'primary',
      handler: (row: MallOrder) => openShipping(row),
      visible: (row: MallOrder) => row.status === 'PAID' && row.refundStatus !== 'PENDING'
    }
  ],
  loadData: async ({ page, pageSize, search }) => {
    const [startTime, endTime] = toTimeRange(search?.createTime);
    const result = await fetchMallOrders({
      current: page,
      size: pageSize,
      orderType: 'MALL',
      status: search?.status || undefined,
      search: String(search?.search ?? '').trim() || undefined,
      startTime,
      endTime
    });
    return {
      data: result.records ?? [],
      total: Number(result.total) || 0
    };
  }
};
</script>

<template>
  <div class="mall-order-page">
    <AdminListPage ref="listRef" :config="config" />

    <DetailModal
      v-model:show="detailVisible"
      title="商城订单详情"
      :groups="detailGroups"
      :row="detailRow"
      :load="loadFreshDetail"
    />
    <DetailModal
      v-model:show="splitVisible"
      title="商城订单分账明细"
      :groups="splitGroups"
      :row="splitRow"
      :load="loadFreshDetail"
    />

    <NModal v-model:show="shippingVisible" preset="card" title="订单发货" class="w-480px">
      <div v-if="shippingRow" class="shipping-order">
        <span>订单号</span>
        <strong>{{ shippingRow.orderNo }}</strong>
      </div>
      <NForm label-placement="top">
        <NFormItem label="快递公司" required>
          <NInput v-model:value="shippingForm.courierCompany" maxlength="64" placeholder="如 顺丰速运" />
        </NFormItem>
        <NFormItem label="快递单号" required>
          <NInput v-model:value="shippingForm.trackingNo" maxlength="64" placeholder="请输入快递单号" />
        </NFormItem>
      </NForm>
      <div class="shipping-hint">确认后订单将变更为“待收货”，并开始 15 天自动完成计时。</div>
      <template #footer>
        <div class="modal-footer">
          <NButton :disabled="shippingSaving" @click="shippingVisible = false">取消</NButton>
          <NButton type="primary" :loading="shippingSaving" @click="submitShipping">确认发货</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.mall-order-page {
  min-width: 0;
}
.shipping-order {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f7f8f5;
  color: #666762;
  font-size: 13px;
}
.shipping-order strong {
  color: #333;
  font-weight: 600;
}
.shipping-hint {
  margin-top: 4px;
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
