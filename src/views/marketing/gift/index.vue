<script setup lang="ts">

defineOptions({
  name: 'marketing_gift'
});

import { h, ref } from 'vue';
import { NImage } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

// 面额编辑弹窗
const faceVisible = ref(false);
const faceRow = ref<any>(null);
const faceText = ref('');

function openFaceEditor(row: any) {
  faceRow.value = row;
  faceText.value = (row.faceValues || []).join(',');
  faceVisible.value = true;
}

function saveFace() {
  if (!faceRow.value) return;
  const values = faceText.value
    .split(/[,，、\s]+/)
    .map((s: string) => s.trim())
    .filter((s: string) => s.length > 0)
    .map((s: string) => Number(s))
    .filter((n: number) => Number.isFinite(n) && n > 0);
  store.update('giftCards', faceRow.value.id, { faceValues: values }, '营销中心', 'name');
  faceVisible.value = false;
  window.$message?.success('面额已保存');
}

const columns: DataTableColumns<any> = [
  { title: '卡种名称', key: 'name', minWidth: 160 },
  {
    title: '卡面',
    key: 'image',
    minWidth: 180,
    render: (row: any) =>
      h(NImage, {
        src: row.image,
        width: 90,
        height: 54,
        objectFit: 'cover',
        style: 'border-radius: 6px',
        previewDisabled: false
      })
  },
  {
    title: '可选面额(元)',
    key: 'faceValues',
    minWidth: 180,
    render: (row: any) => (row.faceValues || []).join(' / ') || '—'
  }
];

const searchFields: SearchField[] = [{ key: 'name', label: '卡种', placeholder: '卡种名称' }];
const formFields: FormField[] = [
  { key: 'name', label: '卡种名称' },
  { key: 'image', label: '卡面图', type: 'image' }
];
const toolbar: RowAction[] = [{ label: '新增卡种', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  { label: '面额', type: 'info', handler: (row: any) => openFaceEditor(row) },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该卡种？（请填写备注）',
    handler: (row, reason) => store.remove('giftCards', row.id, '营销中心', 'name', reason)
  }
];
const config: AdminListConfig = {
  title: '礼品卡',
  remoteKey: 'giftCards',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.listFiltered(store.giftCards, search, page, pageSize),
  form: {
    title: '礼品卡',
    fields: formFields,
    onSubmit: (data, editing) => {
      const payload = { ...data, faceValues: editing?.faceValues || [] };
      if (editing) store.update('giftCards', editing.id, payload, '营销中心', 'name');
      else store.add('giftCards', payload, '营销中心', 'name');
    }
  }
};
</script>

<template>
  <div class="page-root">

  <AdminListPage :config="config" />

  <NModal v-model:show="faceVisible" preset="card" title="可选面额配置" class="w-480px">
    <div v-if="faceRow">
      <div class="face-name">卡种：{{ faceRow.name }}</div>
      <NInput v-model:value="faceText" placeholder="如：100,200,500" />
      <div class="mt-8px text-12px color-#9B9B96">多个面额用逗号分隔</div>
      <div class="flex flex-wrap justify-end gap-12px mt-20px">
        <NButton @click="faceVisible = false">取消</NButton>
        <NButton type="primary" @click="saveFace">保存</NButton>
      </div>
    </div>
  </NModal>
  </div>
</template>

<style scoped>
.face-name {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  margin-bottom: 16px;
}
</style>

