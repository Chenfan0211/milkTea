<script setup lang="ts">
import { computed, h } from 'vue';
import { NTag } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';

defineOptions({
  name: 'Home'
});

const appStore = useAppStore();

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

const kpis: Kpi[] = [
  { key: 'order', label: '今日订单', value: '126', color: '#53882C' },
  { key: 'paid', label: '今日实付(元)', value: '2,418.00', color: '#3F6E1F' },
  { key: 'verify', label: '待核销', value: '18', color: '#E6A23C' },
  { key: 'settle', label: '待结算(元)', value: '6,120.00', color: '#C65A1E' },
  { key: 'issue', label: '分账异常', value: '3', color: '#E6A23C' }
];

interface Row {
  id: number;
  orderNo: string;
  title: string;
  type: string;
  amount: string;
  status: string;
  time: string;
}

const pendingRows: Row[] = [
  {
    id: 1,
    orderNo: 'O202609180021',
    title: '抹茶芝士芭乐 x1',
    type: '退款',
    amount: '¥18.90',
    status: '待审核',
    time: '10:22'
  },
  {
    id: 2,
    orderNo: 'O202609180019',
    title: '红苹果乌龙冰奶 x2',
    type: '退款',
    amount: '¥29.80',
    status: '待审核',
    time: '09:41'
  },
  {
    id: 3,
    orderNo: 'O202609180013',
    title: '五窨茉莉抹茶 x1',
    type: '对账',
    amount: '¥13.90',
    status: '金额差异',
    time: '09:08'
  }
];

const statusType = (status: string) => {
  if (status === '待审核') return 'warning';
  if (status === '金额差异') return 'error';
  return 'default';
};

const columns = [
  { title: '订单号', key: 'orderNo', width: 150 },
  { title: '内容', key: 'title', minWidth: 160 },
  { title: '类型', key: 'type', width: 80 },
  { title: '金额', key: 'amount', width: 90 },
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
      <NGrid cols="s:2 m:3 l:5" :x-gap="gap" :y-gap="16" responsive="screen">
        <NGi v-for="item in kpis" :key="item.key">
          <div class="rd-8px px-16px py-14px" :style="{ backgroundColor: item.color + '14' }">
            <p class="text-14px text-#777873">{{ item.label }}</p>
            <p class="mt-6px text-28px font-bold" :style="{ color: item.color }">{{ item.value }}</p>
          </div>
        </NGi>
      </NGrid>
    </NCard>

    <NCard :bordered="false" class="card-wrapper" title="待处理异常 / 待审核退款">
      <template #header-extra>
        <span class="text-12px text-#9B9B96">演示数据，待接入 /api/v1/admin</span>
      </template>
      <NDataTable :columns="columns" :data="pendingRows" :bordered="false" :row-key="(row: Row) => row.id" />
    </NCard>
  </NSpace>
</template>

<style scoped></style>
