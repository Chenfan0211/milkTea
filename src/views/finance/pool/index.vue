<script setup lang="ts">

defineOptions({
  name: 'finance_pool'
});

import { computed, h, onMounted } from 'vue';
import { NCard, NGrid, NGi, NStatistic, NDataTable } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderMoney } from '@/views/_shared/render';

const store = useAdminStore();

// 挂载时从后端加载资金池与主体账户
onMounted(() => {
  store.loadRemoteAll(['fundPool', 'subjectAccounts']);
});

const pool = computed(() => store.fundPool[0] || { totalBalance: 0 });

const totalAvailable = computed(() =>
  store.subjectAccounts.reduce((sum: number, a: any) => sum + (a.availableBalance || 0), 0)
);
const totalFrozen = computed(() =>
  store.subjectAccounts.reduce((sum: number, a: any) => sum + (a.frozenBalance || 0), 0)
);
const platformBalance = computed(() => {
  const acc = store.subjectAccounts.find((a: any) => a.roleType === 'platform');
  return acc?.availableBalance ?? 0;
});

const roleLabel = (v: string) =>
  (({ platform: '平台', store: '门店', resource: '资源方', investor: '投资人', supplier: '供应商' }) as Record<string, string>)[v] ?? v;

const columns: DataTableColumns<any> = [
  { title: '经营方', key: 'subjectName', width: 160 },
  { title: '角色', key: 'roleType', width: 90, render: (row: any) => roleLabel(row.roleType) },
  { title: '可提现余额(元)', key: 'availableBalance', width: 140, align: 'right', render: renderMoney('availableBalance') },
  { title: '冻结余额(元)', key: 'frozenBalance', width: 120, align: 'right', render: renderMoney('frozenBalance') },
  { title: '累计应得(元)', key: 'totalIncome', width: 130, align: 'right', render: renderMoney('totalIncome') },
  { title: '累计已提现(元)', key: 'totalWithdrawn', width: 140, align: 'right', render: renderMoney('totalWithdrawn') }
];

const stats = computed(() => [
  { label: '池子总余额', value: pool.value.totalBalance },
  { label: '可提现总额', value: totalAvailable.value },
  { label: '冻结总额', value: totalFrozen.value },
  { label: '平台收益(池内)', value: platformBalance.value }
]);
</script>

<template>
  <div class="page-root">
    <NGrid :cols="4" :x-gap="16" :y-gap="16">
      <NGi v-for="s in stats" :key="s.label">
        <NCard :bordered="false" size="small">
          <NStatistic :label="s.label" :value="s.value">
            <template #suffix>元</template>
          </NStatistic>
        </NCard>
      </NGi>
    </NGrid>

    <NCard :bordered="false" class="mt-16px" title="各经营方余额">
      <NDataTable :columns="columns" :data="store.subjectAccounts" :bordered="false" size="small" />
    </NCard>
  </div>
</template>

<style scoped>
.page-root {
  padding: 0;
}
</style>

