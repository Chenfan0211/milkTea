<script setup lang="ts">

defineOptions({
  name: 'marketing_member'
});

import { ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

// 权益编辑弹窗
const benefitsVisible = ref(false);
const benefitsRow = ref<any>(null);
const benefitsText = ref('');

function openBenefitsEditor(row: any) {
  benefitsRow.value = row;
  benefitsText.value = (row.benefits || []).join('、');
  benefitsVisible.value = true;
}

function saveBenefits() {
  if (!benefitsRow.value) return;
  const benefits = benefitsText.value
    .split(/[,，、]/)
    .map((s: string) => s.trim())
    .filter((s: string) => s.length > 0);
  store.update('memberLevels', benefitsRow.value.id, { benefits }, '营销中心', 'name');
  benefitsVisible.value = false;
  window.$message?.success('权益已保存');
  listRef.value?.reload();
}

const columns: DataTableColumns<any> = [
  { title: '等级', key: 'level', width: 80 },
  { title: '名称', key: 'name', width: 120 },
  { title: '门槛(元)', key: 'amountTarget', width: 100, align: 'right' },
  { title: '升级条件', key: 'condition', width: 150, render: (row: any) => row.condition || '—' },
  { title: '折扣', key: 'discount', width: 80 },
  { title: '权益', key: 'benefits', minWidth: 240, render: (row: any) => (row.benefits || []).join('、') }
];
const searchFields: SearchField[] = [{ key: 'name', label: '名称', placeholder: '等级名称' }];
const formFields: FormField[] = [
  { key: 'level', label: '等级' },
  { key: 'name', label: '名称' },
  { key: 'amountTarget', label: '门槛(元)', type: 'number' },
  { key: 'condition', label: '升级条件' },
  { key: 'discount', label: '折扣' }
];
const toolbar: RowAction[] = [{ label: '新增等级', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  { label: '权益', type: 'info', handler: (row: any) => openBenefitsEditor(row) },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该等级？（请填写备注）',
    handler: (row, reason) => store.remove('memberLevels', row.id, '营销中心', 'name', reason)
  }
];
const config: AdminListConfig = {
  title: '会员等级',
  remoteKey: 'memberLevels',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.memberLevels, search, page, pageSize),
  form: {
    title: '会员等级',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('memberLevels', editing.id, data, '营销中心', 'name');
      else store.add('memberLevels', { ...data, benefits: [] }, '营销中心', 'name');
    }
  }
};
</script>

<template>
  <div class="page-root">

  <AdminListPage ref="listRef" :config="config" />

  <NModal v-model:show="benefitsVisible" preset="card" title="权益配置" class="w-560px">
    <div v-if="benefitsRow">
      <div class="benefits-name">等级：{{ benefitsRow.name }}（{{ benefitsRow.level }}）</div>
      <NInput v-model:value="benefitsText" type="textarea" :rows="6" placeholder="多个权益用逗号或顿号分隔" />
      <div class="mt-8px text-12px color-#9B9B96">多个权益用逗号、顿号分隔</div>
      <div class="flex flex-wrap justify-end gap-12px mt-20px">
        <NButton @click="benefitsVisible = false">取消</NButton>
        <NButton type="primary" @click="saveBenefits">保存</NButton>
      </div>
    </div>
  </NModal>
  </div>
</template>

<style scoped>
.benefits-name {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  margin-bottom: 16px;
}
</style>

