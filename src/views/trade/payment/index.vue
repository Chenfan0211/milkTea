<script setup lang="ts">

defineOptions({
  name: 'trade_payment'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney, renderPayChannel } from '@/views/_shared/render';
import { retryAdminPayment } from '@/service/api/trade';

const store = useAdminStore();

/**
 * 支付记录展示口径。
 *
 * 两个「订单号」的区别（易混淆，务必区分）：
 * - 订单号 orderNo     —— 系统订单号（储值充值为 CZ 储值单号）；
 * - 流水订单号 tradeNo —— 三方（微信）订单号，即 transaction_id；
 *   储值支付时该值等于订单号。
 *
 * 状态只保留「三方状态」一列：标准状态（standardStatus）不展示，
 * 但它仍参与「异常重试」按钮的可点判断，故不能从数据里删掉。
 */
const columns: DataTableColumns<any> = [
  { title: '订单号', key: 'orderNo', width: 190 },
  { title: '流水订单号', key: 'transactionId', width: 190, render: (row: any) => row.transactionId || '—' },
  { title: '金额(元)', key: 'amount', width: 110, align: 'right', render: renderMoney('amount') },
  { title: '支付渠道', key: 'channel', width: 110, render: renderPayChannel('channel') },
  {
    title: '三方状态',
    key: 'thirdStatus',
    width: 110,
    render: renderTag(
      'thirdStatus',
      statusMap({
        SUCCESS: ['成功', 'success'],
        PENDING: ['处理中', 'warning'],
        PROCESSING: ['处理中', 'info'],
        NOTPAY: ['未支付', 'info'],
        USERPAYING: ['支付中', 'info'],
        CLOSED: ['已关闭', 'default'],
        REVOKED: ['已撤销', 'default'],
        REFUNDING: ['退款中', 'warning'],
        REFUND: ['已退款', 'warning'],
        PAYERROR: ['支付失败', 'error'],
        FAIL: ['失败', 'error'],
        DUPLICATE_PAY: ['重复支付', 'error']
      })
    )
  }
];

const searchFields: SearchField[] = [
  // 订单号：后端同时匹配 order_no 与 biz_no，储值单号也能一次命中
  { key: 'orderNo', label: '订单号', placeholder: '系统订单号 / 储值单号' },
  // 流水订单号：三方订单号（transaction_id）
  { key: 'tradeNo', label: '流水订单号', placeholder: '三方订单号' },
  {
    key: 'standardStatus',
    label: '状态',
    type: 'select',
    options: [
      { label: '已支付', value: 'PAID' },
      { label: '支付中', value: 'PAYING' },
      { label: '已关闭', value: 'CLOSED' },
      { label: '退款中', value: 'REFUNDING' },
      { label: '已退款', value: 'REFUNDED' },
      { label: '支付失败', value: 'FAILED' }
    ]
  }
];

const toolbar: RowAction[] = [];

/**
 * 行操作：仅「异常重试」。
 *
 * 两处刻意的收敛：
 * 1. 去掉「主动查询」——与「异常重试」职责重叠（重试本质就是主动查询），
 *    且原实现只弹提示、不产生任何实际动作，属于无意义按钮；
 * 2. 「异常重试」只对支付失败的单可见 —— 支付中的单用户可能正在收银台，
 *    手动重试会与用户操作打架；已关闭或已支付的单无需重试。
 */
const rowActions: RowAction[] = [
  {
    label: '异常重试',
    type: 'warning',
    reasonPrompt: '将重新向三方查询该笔支付的真实状态，确认重试？（请填写备注）',
    visible: (row: any) => String(row.standardStatus || '').toUpperCase() === 'FAILED',
    handler: async (row: any, reason?: string) => {
      // 真重试：由后端调三方查询接口，按返回结果回写；
      // 后端不会把状态直接改成成功，故这里不能用乐观更新，必须回读结果。
      const result = await retryAdminPayment(row.id);
      const label = result?.standardStatus ?? row.standardStatus;
      window.$message?.success(`重试完成，当前状态：${label}`);
      await store.loadRemote('payments');
      // reason 由 reasonPrompt 收集并记入审计，重试本身不落库到 payment 表
      void reason;
    }
  }
];

const config: AdminListConfig = {
  title: '支付记录',
  remoteKey: 'payments',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('payments', search, page, pageSize)
};
</script>

<template>
  <div class="page-root">
    <AdminListPage :config="config" />
  </div>
</template>

<style scoped></style>
