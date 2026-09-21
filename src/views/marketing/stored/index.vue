<script setup lang="ts">

defineOptions({
  name: 'marketing_stored'
});

import { ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import GiftCouponsEditor from './GiftCouponsEditor.vue';

const store = useAdminStore();
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

// 赠送券编辑弹窗状态
const giftVisible = ref(false);
const giftRow = ref<any>(null);
const giftModel = ref<any[]>([]);

// 使用说明编辑弹窗状态
const usageVisible = ref(false);
const usageRow = ref<any>(null);
const usageText = ref('');

function openGiftEditor(row: any) {
  giftRow.value = row;
  giftModel.value = JSON.parse(JSON.stringify(row.coupons || []));
  giftVisible.value = true;
}

function saveGift() {
  if (!giftRow.value) return;
  store.update('storedValuePackages', giftRow.value.id, { coupons: giftModel.value }, '营销中心', 'amount');
  giftVisible.value = false;
  window.$message?.success('赠送券已保存');
  listRef.value?.reload();
}

function openUsageEditor(row: any) {
  usageRow.value = row;
  usageText.value = (row.usageParagraphs || []).join('\n');
  usageVisible.value = true;
}

function saveUsage() {
  if (!usageRow.value) return;
  const paragraphs = usageText.value
    .split('\n')
    .map((s: string) => s.trim())
    .filter((s: string) => s.length > 0);
  store.update('storedValuePackages', usageRow.value.id, { usageParagraphs: paragraphs }, '营销中心', 'amount');
  usageVisible.value = false;
  window.$message?.success('使用说明已保存');
  listRef.value?.reload();
}

const columns: DataTableColumns<any> = [
  { title: '套餐金额(元)', key: 'amount', width: 140, align: 'right' },
  {
    title: '赠送券',
    key: 'coupons',
    minWidth: 320,
    render: (row: any) =>
      (row.coupons || []).map((c: any) => `${c.amount}元x${c.quantity}张`).join('、') || '—'
  }
];

const searchFields: SearchField[] = [{ key: 'amount', label: '金额', placeholder: '金额' }];
const formFields: FormField[] = [{ key: 'amount', label: '套餐金额(元)', type: 'number' }];
const toolbar: RowAction[] = [{ label: '新增套餐', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  { label: '赠送券', type: 'info', handler: (row: any) => openGiftEditor(row) },
  { label: '使用说明', type: 'info', handler: (row: any) => openUsageEditor(row) },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该套餐？（请填写备注）',
    handler: (row, reason) => store.remove('storedValuePackages', row.id, '营销中心', 'amount', reason)
  }
];

const config: AdminListConfig = {
  title: '储值套餐',
  remoteKey: 'storedValuePackages',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.storedValuePackages, search, page, pageSize),
  form: {
    title: '储值套餐',
    fields: formFields,
    onSubmit: (data, editing) => {
      if (editing) store.update('storedValuePackages', editing.id, data, '营销中心', 'amount');
      else store.add('storedValuePackages', { ...data, coupons: [], usageParagraphs: [] }, '营销中心', 'amount');
    }
  }
};
</script>

<template>
  <div class="page-root">

  <AdminListPage ref="listRef" :config="config" />

  <NModal v-model:show="giftVisible" preset="card" title="赠送券配置" class="w-680px">
    <div v-if="giftRow" class="modal-body">
      <div class="modal-name">套餐金额：¥{{ giftRow.amount }}</div>
      <GiftCouponsEditor v-model="giftModel" />
      <div class="flex flex-wrap justify-end gap-12px mt-20px">
        <NButton @click="giftVisible = false">取消</NButton>
        <NButton type="primary" @click="saveGift">保存</NButton>
      </div>
    </div>
  </NModal>

  <NModal v-model:show="usageVisible" preset="card" title="使用说明配置" class="w-680px">
    <div v-if="usageRow" class="modal-body">
      <div class="modal-name">套餐金额：¥{{ usageRow.amount }}</div>
      <NInput
        v-model:value="usageText"
        type="textarea"
        :rows="8"
        placeholder="每行一条使用说明"
      />
      <div class="mt-8px text-12px color-#9B9B96">每行一条，保存时自动按换行拆分</div>
      <div class="flex flex-wrap justify-end gap-12px mt-20px">
        <NButton @click="usageVisible = false">取消</NButton>
        <NButton type="primary" @click="saveUsage">保存</NButton>
      </div>
    </div>
  </NModal>
  </div>
</template>

<style scoped>
.modal-body {
  max-height: 60vh;
  overflow-y: auto;
}
.modal-name {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  margin-bottom: 16px;
}
</style>

