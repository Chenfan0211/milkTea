<script setup lang="ts">
import { computed, h, onMounted } from 'vue';
import { NTag } from 'naive-ui';
import { formatDateTime } from '@/views/_shared/render';
import { useAppStore } from '@/store/modules/app';
import { useAdminStore } from '@/store/modules/admin';
import { useEcharts, type ECOption } from '@/hooks/common/echarts';

defineOptions({
  name: 'Home'
});

const appStore = useAppStore();
const adminStore = useAdminStore();

// 仪表盘数据来自后端接口，挂载时拉取订单/退款/提现
onMounted(() => {
  // reconciles 也用于首页「待处理异常」表，原先漏加载会导致该区块读本地假数据\n  adminStore.loadRemoteAll(['orders', 'refunds', 'withdrawals', 'reconciles']);
});

const gap = computed(() => (appStore.isMobile ? 0 : 16));

const today = computed(() => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
});

interface Kpi {
  key: string;
  label: string;
  value: string;
  color: string;
}

const formatMoney = (fen: number) =>
  (fen / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const isToday = (time: string) => time.startsWith(today.value);

const kpis = computed<Kpi[]>(() => {
  const orders = adminStore.orders;
  const todayOrders = orders.filter(o => isToday(o.createTime));
  const todayPaidFen = todayOrders.reduce((sum, o) => sum + (Number(o.paidAmount) || 0), 0);
  const pendingVerify = orders.filter(o => o.status === 'PAID').length;
  const settleableFen = orders
    .filter(o => o.status === 'COMPLETED')
    .reduce((sum, o) => sum + (Number(o.paidAmount) || 0), 0);
  const pendingRefund = adminStore.refunds.filter(r => r.status === 'FAILED').length;
  const pendingWithdraw = adminStore.withdrawals.filter(w => w.status === 'pending').length;

  return [
    { key: 'order', label: '今日订单', value: String(todayOrders.length), color: '#53882C' },
    { key: 'paid', label: '今日实付(元)', value: formatMoney(todayPaidFen), color: '#3F6E1F' },
    { key: 'verify', label: '待核销', value: String(pendingVerify), color: '#E6A23C' },
    { key: 'settle', label: '待结算(元)', value: formatMoney(settleableFen), color: '#C65A1E' },
    { key: 'refund', label: '退款失败', value: String(pendingRefund), color: '#E6A23C' },
    { key: 'withdraw', label: '待审核提现', value: String(pendingWithdraw), color: '#C65A1E' }
  ];
});

function buildTrendData() {
  const days: string[] = [];
  const orderCounts: number[] = [];
  const paidFens: number[] = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date();
    d.setDate(d.getDate() - i);
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    days.push(`${d.getMonth() + 1}/${d.getDate()}`);
    const dayOrders = adminStore.orders.filter(o => o.createTime.startsWith(key));
    orderCounts.push(dayOrders.length);
    paidFens.push(dayOrders.reduce((s, o) => s + (Number(o.paidAmount) || 0), 0) / 100);
  }
  return { days, orderCounts, paidFens };
}

const trendOptions = computed(() => {
  const { days, orderCounts, paidFens } = buildTrendData();
  return {
    color: ['#53882C', '#E6A23C'],
    tooltip: { trigger: 'axis' },
    legend: { data: ['订单数', '实付(元)'], bottom: 0 },
    grid: { left: 45, right: 45, top: 30, bottom: 40 },
    xAxis: { type: 'category', data: days, boundaryGap: false },
    yAxis: [
      { type: 'value', name: '订单数', minInterval: 1 },
      { type: 'value', name: '实付(元)' }
    ],
    series: [
      { name: '订单数', type: 'line', smooth: true, data: orderCounts },
      { name: '实付(元)', type: 'line', smooth: true, yAxisIndex: 1, data: paidFens }
    ]
  } as ECOption;
});

const statusLabel: Record<string, string> = {
  CREATED: '待支付',
  PAID: '待核销',
  COMPLETED: '已完成',
  CANCELED: '已取消'
};

const pieOptions = computed(() => {
  const map: Record<string, number> = {};
  adminStore.orders.forEach(o => {
    map[o.status] = (map[o.status] || 0) + 1;
  });
  const data = Object.entries(map).map(([k, v]) => ({ name: statusLabel[k] || k, value: v }));
  const colorMap: Record<string, string> = {
    CREATED: '#9B9B96',
    PAID: '#E6A23C',
    COMPLETED: '#3F6E1F',
    CANCELED: '#9B9B96'
  };
  return {
    color: data.map(d => colorMap[Object.keys(statusLabel).find(k => statusLabel[k] === d.name) || ''] || '#9B9B96'),
    tooltip: { trigger: 'item', formatter: '{b}: {c} 单 ({d}%)' },
    legend: { bottom: 0 },
    series: [
      {
        name: '订单状态',
        type: 'pie',
        radius: ['40%', '68%'],
        center: ['50%', '45%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        emphasis: { label: { show: true, fontWeight: 'bold' } },
        data
      }
    ]
  } as ECOption;
});

const { domRef: trendDomRef } = useEcharts(() => trendOptions.value);
function setTrendRef(el: unknown) {
  trendDomRef.value = el as HTMLElement | null;
}
const { domRef: pieDomRef } = useEcharts(() => pieOptions.value);
function setPieRef(el: unknown) {
  pieDomRef.value = el as HTMLElement | null;
}

interface Row {
  id: string;
  orderNo: string;
  title: string;
  type: string;
  amount: string;
  status: string;
  time: string;
}

const pendingRows = computed<Row[]>(() => {
  const rows: Row[] = [];

  adminStore.refunds
    .filter(r => r.status === 'PENDING')
    .forEach(r => {
      rows.push({
        id: `refund-${r.id}`,
        orderNo: r.orderNo,
        title: r.title || r.product || '退款单',
        type: '退款',
        amount: `¥${formatMoney(r.amount)}`,
        status: '待审核',
        time: formatDateTime(r.applyTime)
      });
    });

  adminStore.reconciles
    .filter(r => r.status === 'open' && (Number(r.diffAmount) || 0) > 0)
    .forEach(r => {
      rows.push({
        id: `reconcile-${r.id}`,
        orderNo: r.orderNo,
        title: r.issueType || '对账异常',
        type: '对账',
        amount: `¥${formatMoney(r.diffAmount)}`,
        status: '金额差异',
        time: formatDateTime(r.foundTime)
      });
    });

  adminStore.withdrawals
    .filter(w => w.status === 'pending')
    .forEach(w => {
      rows.push({
        id: `withdraw-${w.id}`,
        orderNo: w.userId || '—',
        title: `${w.nickName || '用户'} 提现申请`,
        type: '提现',
        amount: `¥${formatMoney(w.amount)}`,
        status: '待审核',
        time: formatDateTime(w.applyTime)
      });
    });

  return rows.slice(0, 10);
});

const statusType = (status: string) => {
  if (status === '待审核') return 'warning';
  if (status === '金额差异') return 'error';
  return 'default';
};

const columns = [
  { title: '单号', key: 'orderNo', width: 145 },
  { title: '内容', key: 'title', minWidth: 180 },
  { title: '类型', key: 'type', width: 80 },
  { title: '金额', key: 'amount', width: 100, align: 'right' as const },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: (row: Row) =>
      h(NTag, { size: 'small', bordered: false, type: statusType(row.status) }, { default: () => row.status })
  },
  { title: '时间', key: 'time', width: 80 }
];
</script>

<template>
  <NSpace vertical :size="16">
    <NCard :bordered="false" class="card-wrapper">
      <div class="flex-y-center justify-between">
        <div>
          <h3 class="text-20px font-semibold text-#2F302D">运营概览</h3>
          <p class="text-#777873 leading-26px">今日 {{ today }} · 灰度门店上线保障中</p>
        </div>
        <div class="text-#9B9B96">五零时光运营后台</div>
      </div>
    </NCard>

    <NCard :bordered="false" class="card-wrapper">
      <NGrid cols="s:2 m:3 l:6" :x-gap="gap" :y-gap="16" responsive="screen">
        <NGi v-for="item in kpis" :key="item.key">
          <div class="rd-8px px-16px py-14px" :style="{ backgroundColor: item.color + '14' }">
            <p class="text-14px text-#777873">{{ item.label }}</p>
            <p class="mt-6px text-26px font-bold" :style="{ color: item.color }">{{ item.value }}</p>
          </div>
        </NGi>
      </NGrid>
    </NCard>

    <NCard :bordered="false" class="card-wrapper">
      <NGrid cols="s:1 m:2" :x-gap="gap" :y-gap="16" responsive="screen">
        <NGi>
          <div class="h-300px">
            <p class="mb-12px text-15px font-semibold text-#2F302D">近 7 日订单趋势</p>
            <div :ref="setTrendRef" class="h-260px" />
          </div>
        </NGi>
        <NGi>
          <div class="h-300px">
            <p class="mb-12px text-15px font-semibold text-#2F302D">订单状态分布</p>
            <div :ref="setPieRef" class="h-260px" />
          </div>
        </NGi>
      </NGrid>
    </NCard>

    <NCard :bordered="false" class="card-wrapper" title="待处理异常 / 待审核退款">
      <template #header-extra>
        <span class="text-12px text-#9B9B96">实时数据，共 {{ pendingRows.length }} 条待办</span>
      </template>
      <NDataTable :columns="columns" :data="pendingRows" :bordered="false" :row-key="(row: Row) => row.id" />
    </NCard>
  </NSpace>
</template>

<style scoped></style>
