<script setup lang="ts">

defineOptions({
  name: 'trade_order'
});

import { h, ref } from 'vue';
import { useRouter } from 'vue-router';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();
const router = useRouter();

// 分账明细弹窗
const splitVisible = ref(false);
const splitRow = ref<any>(null);

function openSplit(row: any) {
  splitRow.value = row;
  splitVisible.value = true;
}

const columns: DataTableColumns<any> = [
  { title: '订单号', key: 'orderNo', width: 160 },
  { title: '门店', key: 'store', width: 130 },
  { title: '用户', key: 'user', width: 110 },
  { title: '商品摘要', key: 'summary', minWidth: 160 },
  { title: '实付金额(元)', key: 'paidAmount', width: 120, align: 'right', render: renderMoney('paidAmount') },
  {
    title: '订单状态',
    key: 'status',
    width: 120,
    render: renderTag(
      'status',
      statusMap({
        CREATED: ['待支付', 'info'],
        PAID: ['已支付', 'primary'],
        VERIFIED: ['已核销', 'success'],
        COMPLETED: ['已完成', 'success'],
        REFUNDED: ['已退款', 'default']
      })
    )
  },
  { title: '取餐码', key: 'pickupCode', width: 100 },
  { title: '创建时间', key: 'createTime', width: 150 }
];

const searchFields: SearchField[] = [
  { key: 'orderNo', label: '订单号', placeholder: '订单号' },
  { key: 'store', label: '门店', placeholder: '门店' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待支付', value: 'CREATED' },
      { label: '已支付', value: 'PAID' },
      { label: '已核销', value: 'VERIFIED' },
      { label: '已完成', value: 'COMPLETED' },
      { label: '已退款', value: 'REFUNDED' }
    ]
  }
];
const toolbar: RowAction[] = [];
const rowActions: RowAction[] = [
  { label: '分账明细', type: 'info', handler: (row: any) => openSplit(row) },
  { label: '详情', type: 'info', handler: row => router.push({ path: '/trade/order-detail', query: { id: row.id } }) },
  {
    label: '取消订单',
    type: 'warning',
    reasonPrompt: '取消后资金原路退回，确认取消该订单？（请填写备注）',
    handler: (row, reason) => store.refundOrder(row.id, reason),
    visible: row => row.status === 'CREATED' || row.status === 'PAID'
  }
];
const config: AdminListConfig = {
  title: '订单管理',
  remoteKey: 'orders',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.orders, search, page, pageSize)
};
</script>

<template>
  <div class="page-root">

  <AdminListPage :config="config" />

  <NModal v-model:show="splitVisible" preset="card" title="分账明细" class="w-560px">
    <div v-if="splitRow" class="split-modal">
      <div class="split-order-no">订单号：{{ splitRow.orderNo }}</div>
      <div class="split-line"><span>实付金额</span><span>¥{{ (splitRow.paidAmount / 100).toFixed(2) }}</span></div>
      <div class="split-line"><span>供应商（成本合计）</span><span>¥{{ (splitRow.split?.costTotal ?? 0).toFixed(2) }}</span></div>
      <div class="split-line"><span>门店（每件×{{ splitRow.split?.itemCount ?? 0 }}件）</span><span>¥{{ (splitRow.split?.storeShare ?? 0).toFixed(2) }}</span></div>
      <div class="split-line"><span>资源方</span><span>¥{{ (splitRow.split?.channelShare ?? 0).toFixed(2) }}</span></div>
      <div class="split-line"><span>投资人（{{ splitRow.split?.base ?? 0 > 0 ? '按比例' : '基础为负' }}）</span><span>¥{{ (splitRow.split?.investorShare ?? 0).toFixed(2) }}</span></div>
      <div class="split-line split-line--total"><span>平台（剩余）</span><span>¥{{ (splitRow.split?.platformShare ?? 0).toFixed(2) }}</span></div>
      <div class="mt-16px text-12px color-#9B9B96">
        公式：实付 - 成本合计 - 门店(每件×件数) - 资源方(每件×件数) - 投资人(基础×比例%)
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

