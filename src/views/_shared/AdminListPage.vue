<script setup lang="ts">
import { computed, h, reactive, ref, watch } from 'vue';
import {
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  NSpace
} from 'naive-ui';
import type { PaginationProps } from 'naive-ui';
import { toSelectOptions } from './types';
import type { AdminListConfig, FormField, RowAction } from './types';

const props = defineProps<{ config: AdminListConfig }>();

const loading = ref(false);
const rows = ref<any[]>([]);

const pagination = reactive<PaginationProps>({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 20, 50],
  onUpdatePage: (page: number) => {
    pagination.page = page;
    loadData();
  },
  onUpdatePageSize: (pageSize: number) => {
    pagination.pageSize = pageSize;
    pagination.page = 1;
    loadData();
  }
});

const search = reactive<Record<string, any>>({});

const modalVisible = ref(false);
const modalMode = ref<'add' | 'edit'>('add');
const editingRow = ref<any>(null);
const formModel = reactive<Record<string, any>>({});

// picker state
const pickerVisible = ref(false);
const pickerTitle = ref('');
const pickerOptions = ref<{ label: string; value: string }[]>([]);
const pickedValue = ref<string | null>(null);
const pendingPickerAction = ref<RowAction | null>(null);
const pendingPickerRow = ref<any>(null);

function openModal(mode: 'add' | 'edit', row: any) {
  modalMode.value = mode;
  editingRow.value = row;
  for (const key of Object.keys(formModel)) {
    formModel[key] = undefined;
  }
  if (mode === 'edit' && row) {
    Object.assign(formModel, row);
  }
  modalVisible.value = true;
}

async function submitModal() {
  if (props.config.form) {
    await props.config.form.onSubmit({ ...formModel }, modalMode.value === 'edit' ? editingRow.value : null);
  }
  modalVisible.value = false;
  loadData();
}

function openPicker(action: RowAction, row: any) {
  pendingPickerAction.value = action;
  pendingPickerRow.value = row;
  pickerTitle.value = action.picker?.title ?? '请选择';
  pickerOptions.value = toSelectOptions(action.picker?.options() ?? []);
  pickedValue.value = null;
  pickerVisible.value = true;
}

function confirmPicker() {
  if (pickedValue.value == null) {
    window.$message?.warning('请先选择');
    return;
  }
  pendingPickerAction.value?.handler?.(pendingPickerRow.value, pickedValue.value);
  pickerVisible.value = false;
  loadData();
}

function handleAction(action: RowAction, row: any) {
  if (action.modal) {
    openModal(action.modal, row);
    return;
  }
  if (action.picker) {
    openPicker(action, row);
    return;
  }
  action.handler?.(row);
  loadData();
}

function renderAction(action: RowAction, row: any) {
  const btn = () =>
    h(
      NButton,
      { size: 'small', type: action.type ?? 'default', onClick: () => handleAction(action, row) },
      { default: () => action.label }
    );
  if (action.confirm) {
    return h(
      NPopconfirm,
      { onPositiveClick: () => handleAction(action, row) },
      { trigger: btn, default: () => action.confirm }
    );
  }
  return btn();
}

function visibleActions(row: any) {
  return (props.config.rowActions ?? []).filter(action => (action.visible ? action.visible(row) : true));
}

const columns = computed(() => {
  const cols: any[] = [...props.config.columns];
  if (props.config.rowActions?.length) {
    cols.push({
      title: '操作',
      key: '__actions__',
      width: 230,
      fixed: 'right',
      render(row: any) {
        return h(NSpace, { size: 8 }, { default: () => visibleActions(row).map(action => renderAction(action, row)) });
      }
    });
  }
  return cols;
});

async function loadData() {
  loading.value = true;
  try {
    const result = await props.config.loadData({
      page: pagination.page ?? 1,
      pageSize: pagination.pageSize ?? 10,
      search
    });
    rows.value = result.data;
    pagination.itemCount = result.total;
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pagination.page = 1;
  loadData();
}

function handleReset() {
  for (const key of Object.keys(search)) {
    search[key] = undefined;
  }
  pagination.page = 1;
  loadData();
}

function renderField(field: FormField) {
  if (field.type === 'select') {
    return h(NSelect, {
      value: formModel[field.key],
      options: toSelectOptions(field.options ?? []),
      clearable: true,
      placeholder: field.placeholder,
      'onUpdate:value': (v: any) => (formModel[field.key] = v)
    });
  }
  if (field.type === 'number') {
    return h(NInputNumber, {
      value: formModel[field.key] ?? null,
      placeholder: field.placeholder,
      'onUpdate:value': (v: any) => (formModel[field.key] = v)
    });
  }
  return h(NInput, {
    value: formModel[field.key] ?? '',
    placeholder: field.placeholder,
    'onUpdate:value': (v: string) => (formModel[field.key] = v)
  });
}

watch(() => props.config, loadData, { immediate: true });
</script>

<template>
  <NCard :bordered="false" :title="config.title" class="card-wrapper">
    <template #header-extra>
      <NSpace>
        <NButton
          v-for="(action, index) in config.toolbar ?? []"
          :key="index"
          size="small"
          :type="action.type ?? 'default'"
          @click="handleAction(action, null)"
        >
          {{ action.label }}
        </NButton>
      </NSpace>
    </template>

    <NForm v-if="config.searchFields?.length" layout="inline" class="mb-12px">
      <NFormItem v-for="field in config.searchFields" :key="field.key" :label="field.label">
        <NInput
          v-if="!field.type || field.type === 'input'"
          v-model:value="search[field.key]"
          :placeholder="field.placeholder"
          clearable
          class="w-180px"
          @keyup.enter="handleSearch"
        />
        <NSelect
          v-else
          v-model:value="search[field.key]"
          :options="toSelectOptions(field.options ?? [])"
          clearable
          class="w-160px"
        />
      </NFormItem>
      <NButton size="small" type="primary" @click="handleSearch">查询</NButton>
      <NButton size="small" class="ml-8px" @click="handleReset">重置</NButton>
    </NForm>

    <NDataTable
      remote
      :columns="columns"
      :data="rows"
      :loading="loading"
      :pagination="pagination"
      :row-key="(row: any) => row.id"
      :bordered="false"
    />
  </NCard>

  <NModal v-model:show="modalVisible" preset="card" :title="config.form?.title ?? '表单'" class="w-560px">
    <NForm label-placement="left" :label-width="90">
      <NFormItem v-for="field in config.form?.fields ?? []" :key="field.key" :label="field.label">
        <component :is="renderField(field)" />
      </NFormItem>
      <div class="flex justify-end gap-12px">
        <NButton @click="modalVisible = false">取消</NButton>
        <NButton type="primary" @click="submitModal">确定</NButton>
      </div>
    </NForm>
  </NModal>

  <NModal v-model:show="pickerVisible" preset="card" :title="pickerTitle" class="w-480px">
    <NSelect v-model:value="pickedValue" :options="pickerOptions" clearable placeholder="请选择" filterable />
    <div class="mt-16px flex justify-end gap-12px">
      <NButton @click="pickerVisible = false">取消</NButton>
      <NButton type="primary" @click="confirmPicker">确定</NButton>
    </div>
  </NModal>
</template>

<style scoped></style>
