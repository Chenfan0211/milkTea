<script setup lang="ts">

defineOptions({
  name: 'finance_withdraw'
});

import { ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig } from '@/views/_shared/types';
import type { SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap, renderMoney } from '@/views/_shared/render';

const store = useAdminStore();
const withdrawVisible = ref(false);
const withdrawSubjectId = ref<number | null>(null);
const withdrawAmount = ref<number | null>(null);
const roleLabel = (v: string) =>
  (({ store: '门店', investor: '投资人', resource: '资源方' }) as Record<string, string>)[v] ?? v;
const columns: DataTableColumns<any> = [
  { title: '申请人', key: 'nickName', width: 140 },
  { title: '角色', key: 'roleType', width: 100, render: (row: any) => roleLabel(row.roleType) },
  { title: '金额(元)', key: 'amount', width: 110, align: 'right', render: renderMoney('amount') },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: renderTag(
      'status',
      statusMap({ pending: ['待审核', 'warning'], approved: ['已通过', 'success'], rejected: ['已驳回', 'error'] })
    )
  },
  { title: '申请时间', key: 'applyTime', width: 150 },
  { title: '审核人', key: 'reviewer', width: 110, render: (row: any) => row.reviewer || '—' }
];
const searchFields: SearchField[] = [
  { key: 'nickName', label: '申请人', placeholder: '昵称' },
  {
    key: 'status',
    label: '状态',
    type: 'select',
    options: [
      { label: '待审核', value: 'pending' },
      { label: '已通过', value: 'approved' },
      { label: '已驳回', value: 'rejected' }
    ]
  }
];
const toolbar: RowAction[] = [{ label: '发起提现', type: 'primary', handler: () => { withdrawVisible.value = true; } }];
const rowActions: RowAction[] = [
  {
    label: '通过',
    type: 'success',
    reasonPrompt: '确认通过该提现申请？（请填写备注）',
    handler: (row, reason) => store.reviewWithdraw(row.id, true, reason),
    visible: row => row.status === 'pending'
  },
  {
    label: '驳回',
    type: 'error',
    reasonPrompt: '确认驳回该提现申请？（请填写备注）',
    handler: (row, reason) => store.reviewWithdraw(row.id, false, reason),
    visible: row => row.status === 'pending'
  }
];
const config: AdminListConfig = {
  title: '提现管理',
  remoteKey: 'withdrawals',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.withdrawals, search, page, pageSize)
};

function submitWithdraw() {
  if (!withdrawSubjectId.value) {
    window.$message?.warning('请选择经营方');
    return;
  }
  if (!withdrawAmount.value || withdrawAmount.value <= 0) {
    window.$message?.warning('请输入提现金额');
    return;
  }
  store.applyWithdraw(withdrawSubjectId.value, withdrawAmount.value);
  withdrawVisible.value = false;
  withdrawSubjectId.value = null;
  withdrawAmount.value = null;
}
</script>

<template>
  <div class="page-root">
    <AdminListPage :config="config" />

    <NModal v-model:show="withdrawVisible" preset="card" title="发起提现" class="w-480px">
      <NSelect
        v-model:value="withdrawSubjectId"
        :options="store.subjectAccounts.filter(a => a.roleType !== 'platform').map(a => ({ label: a.subjectName + '（可提现 ¥' + (a.availableBalance ?? 0).toFixed(2) + '）', value: a.subjectId }))"
        clearable
        filterable
        placeholder="选择经营方"
      />
      <NInputNumber v-model:value="withdrawAmount" :min="0" placeholder="提现金额（元）" class="mt-12px w-full" />
      <div class="mt-16px flex flex-wrap justify-end gap-12px">
        <NButton @click="withdrawVisible = false">取消</NButton>
        <NButton type="primary" @click="submitWithdraw">确定</NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped></style>

