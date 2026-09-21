<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import {
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NImage,
  NUpload,
  NUploadDragger,
  NDatePicker,
  NPopconfirm,
  NCheckbox,
  NPopover,
  NSelect,
  NSpace
} from 'naive-ui';
import type { PaginationProps, UploadFileInfo } from 'naive-ui';
import { toSelectOptions } from './types';
import { useAdminStore } from '@/store/modules/admin';
import * as XLSX from 'xlsx';
import { geocodeAddress } from '@/utils/tencent-map';
import type { AdminListConfig, FormField, RowAction } from './types';

const props = defineProps<{ config: AdminListConfig }>();

const loading = ref(false);
const rows = ref<any[]>([]);

// 列显示设置
const columnSettingsVisible = ref(false);
const hiddenColumns = ref<string[]>([]);

function columnKeyOf(col: any, index: number): string {
  return String(col.key ?? col.title ?? `col_${index}`);
}

const columnOptions = computed(() =>
  (props.config.columns ?? []).map((col: any, index: number) => ({
    label: String(col.title ?? col.key ?? `列${index + 1}`),
    value: columnKeyOf(col, index)
  }))
);

function toggleAllColumns(checked: boolean) {
  hiddenColumns.value = checked ? [] : columnOptions.value.map((o: any) => o.value);
}

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

// 初始化初始搜索条件（URL 回显等）
watch(
  () => props.config.initialSearch,
  val => {
    if (!val) return;
    for (const key of Object.keys(val)) {
      search[key] = val[key];
    }
  },
  { immediate: true }
);

const modalVisible = ref(false);
const modalMode = ref<'add' | 'edit'>('add');
const editingRow = ref<any>(null);
const formModel = reactive<Record<string, any>>({});

const reasonVisible = ref(false);
const reasonTitle = ref('');
const reasonText = ref('');
const pendingReasonAction = ref<RowAction | null>(null);
const pendingReasonRow = ref<any>(null);

// picker state
const pickerVisible = ref(false);
const pickerTitle = ref('');
const pickerOptions = ref<{ label: string; value: string }[]>([]);
const pickedValue = ref<string | null>(null);
const pendingPickerAction = ref<RowAction | null>(null);
const pendingPickerRow = ref<any>(null);
const pendingGroupAction = ref<RowAction | null>(null);
const pendingGroupRow = ref<any>(null);
const groupSelectVisible = ref(false);
const groupSelectOptions = ref<any[]>([]);
const groupPickedValue = ref<string | null>(null);

// cascade picker state
const cascadeVisible = ref(false);
const cascadeTitle = ref('');
const cascadeFirstOptions = ref<{ label: string; value: string }[]>([]);
const cascadeSecondOptions = ref<{ label: string; value: string }[]>([]);
const cascadeFirstValue = ref<string | null>(null);
const cascadeSecondValue = ref<string | null>(null);
const pendingCascadeAction = ref<RowAction | null>(null);
const pendingCascadeRow = ref<any>(null);

// import state
const importVisible = ref(false);
const importRows = ref<Record<string, string>[]>([]);
const importErrors = ref<string[]>([]);
const importPreview = ref<Record<string, any>[]>([]);
const importFileName = ref('');

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
  pickerOptions.value = toSelectOptions(action.picker?.options?.(row) ?? []);
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

function openReason(action: RowAction, row: any) {
  pendingReasonAction.value = action;
  pendingReasonRow.value = row;
  reasonTitle.value = action.reasonPrompt ?? '请填写备注';
  reasonText.value = '';
  reasonVisible.value = true;
}

function confirmReason() {
  const reason = reasonText.value.trim();
  if (!reason) {
    window.$message?.warning('请填写备注信息');
    return;
  }
  pendingReasonAction.value?.handler?.(pendingReasonRow.value, reason);
  reasonVisible.value = false;
  loadData();
}

function openGroupPicker(action: RowAction, row: any) {
  pendingGroupAction.value = action;
  pendingGroupRow.value = row;
  groupPickedValue.value = null;
  groupSelectOptions.value = (action.pickerGroup?.groups ?? []).map(g => ({
    type: 'group',
    label: g.label,
    key: g.key,
    children: (g.options?.() ?? []).map(o => ({ label: o.label, key: `${g.key}:${o.value}` }))
  }));
  groupSelectVisible.value = true;
}

function confirmGroupPicker() {
  if (groupPickedValue.value == null) {
    window.$message?.warning('请先选择');
    return;
  }
  const picked = groupPickedValue.value;
  const [groupKey, value] = picked.split(':');
  pendingGroupAction.value?.handler?.(pendingGroupRow.value, picked, groupKey, value);
  groupSelectVisible.value = false;
  loadData();
}

function openCascadePicker(action: RowAction, row: any) {
  pendingCascadeAction.value = action;
  pendingCascadeRow.value = row;
  cascadeTitle.value = action.cascadePicker?.title ?? '请选择';
  const steps = action.cascadePicker?.steps;
  cascadeFirstOptions.value = toSelectOptions(steps?.[0]?.options?.() ?? []);
  cascadeSecondOptions.value = [];
  cascadeFirstValue.value = null;
  cascadeSecondValue.value = null;
  cascadeVisible.value = true;
}

function onCascadeFirstChange(value: string) {
  cascadeFirstValue.value = value;
  cascadeSecondValue.value = null;
  const steps = pendingCascadeAction.value?.cascadePicker?.steps;
  cascadeSecondOptions.value = toSelectOptions(steps?.[1]?.options?.(value) ?? []);
}

function confirmCascadePicker() {
  if (!cascadeFirstValue.value || !cascadeSecondValue.value) {
    window.$message?.warning('请先选择');
    return;
  }
  pendingCascadeAction.value?.cascadePicker?.handler?.(pendingCascadeRow.value, {
    first: cascadeFirstValue.value,
    second: cascadeSecondValue.value
  });
  cascadeVisible.value = false;
  loadData();
}


function openImport() {
  importVisible.value = true;
  importRows.value = [];
  importErrors.value = [];
  importPreview.value = [];
  importFileName.value = '';
}

function parseWorkbook(data: ArrayBuffer): Record<string, string>[] {
  const wb = XLSX.read(data, { type: 'array' });
  const sheet = wb.Sheets[wb.SheetNames[0]];
  const json = XLSX.utils.sheet_to_json<Record<string, any>>(sheet, { defval: '' });
  const fields = props.config.importConfig?.fields ?? [];
  return json.map(row => {
    const out: Record<string, string> = {};
    for (const f of fields) out[f.key] = String(row[f.label] ?? row[f.key] ?? '').trim();
    return out;
  });
}

function handleImportFile(options: { file: any }) {
  const file = options.file;
  if (!file) return;
  importFileName.value = file.name || '';
  const reader = new FileReader();
  reader.addEventListener('load', (e: any) => {
    try {
      const parsedRows = parseWorkbook(e.target.result);
      const result = props.config.importConfig?.parse?.(parsedRows) ?? { ok: parsedRows, errors: [] };
      importRows.value = parsedRows;
      importPreview.value = result.ok;
      importErrors.value = result.errors;
    } catch (err: any) {
      importErrors.value = [String(err?.message || err)];
      importPreview.value = [];
    }
  });
  reader.readAsArrayBuffer(file.file);
}

function downloadTemplate() {
  const cfg = props.config.importConfig;
  if (!cfg) return;
  const text = cfg.template();
  const blob = new Blob(['\ufeff' + text], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = cfg.title + '导入模板.csv';
  a.click();
  URL.revokeObjectURL(url);
}

function confirmImport() {
  const cfg = props.config.importConfig;
  if (!cfg || !importPreview.value.length) {
    window.$message?.warning('没有可导入的数据');
    return;
  }
  const result = cfg.commit(importPreview.value);
  importVisible.value = false;
  window.$message?.success('导入完成：新增 ' + result.added + ' 条' + (result.skipped ? '，跳过 ' + result.skipped + ' 条' : ''));
  loadData();
}

function handleAction(action: RowAction, row: any) {
  if (action.modal) {
    openModal(action.modal, row);
    return;
  }
  if (action.reasonPrompt) {
    openReason(action, row);
    return;
  }
  if (action.picker) {
    openPicker(action, row);
    return;
  }
  if (action.pickerGroup) {
    openGroupPicker(action, row);
    return;
  }
  if (action.cascadePicker) {
    openCascadePicker(action, row);
    return;
  }
  action.handler?.(row);
  loadData();
}

function renderAction(action: RowAction, row: any) {
  const btn = () =>
    h(NButton, { size: 'small', type: action.type ?? 'default', onClick: () => handleAction(action, row) }, { default: () => action.label });
  if (action.confirm) {
    return h(
      NPopconfirm,
      { onPositiveClick: () => handleAction(action, row) },
      { trigger: btn, default: () => action.confirm }
    );
  }
  return btn();
}

const rowActionsWidth = computed(() => {
  const acts = props.config.rowActions ?? [];
  if (acts.length <= 2) return 140;
  if (acts.length <= 3) return 210;
  if (acts.length <= 4) return 280;
  return 340;
});

function visibleActions(row: any) {
  return (props.config.rowActions ?? []).filter(action => (action.visible ? action.visible(row) : true));
}

const columns = computed(() => {
  const visibleCols = (props.config.columns ?? []).filter(
    (col: any, index: number) => !hiddenColumns.value.includes(columnKeyOf(col, index))
  );
  // 统一：文本列默认省略 + 悬停 tooltip，避免长文本撑宽表格
  const cols: any[] = visibleCols.map((col: any) => {
    if (col.ellipsis === false || col.ellipsis != null) return col;
    if (col.key === '__actions__') return col;
    if (col.type === 'selection' || col.type === 'expand') return col;
    return { ...col, ellipsis: { tooltip: true } };
  });
  if (props.config.rowActions?.length) {
    cols.push({
      title: '操作',
      key: '__actions__',
      width: rowActionsWidth.value,

      render(row: any) {
        if (row.deleted) return h('span', { style: 'color:#9B9B96' }, '已删除');
        return h(NSpace, { size: 8, wrap: true }, { default: () => visibleActions(row).map(action => renderAction(action, row)) });
      }
    });
  }
  return cols;
});

// 列总宽（超出容器时表格内部横向滚动，不撑破页面）
const scrollX = computed(() => {
  // 仅固定 width 列计入横向滚动；minWidth 弹性列由表格自动分配，避免超屏
  const fixed = columns.value.reduce((sum: number, col: any) => sum + (col.width ?? 0), 0);
  return Math.min(fixed, 1200);
});

const adminStore = useAdminStore();

/**
 * 远端数据预加载：页面声明了 remoteKey / remoteDeps 时，
 * 先拉取后端资源到 store 镜像，再执行列表加载，避免首屏读到空数据。
 */
onMounted(async () => {
  const deps = [props.config.remoteKey, ...(props.config.remoteDeps || [])].filter(Boolean) as string[];
  const remoteDeps = deps.filter(key => adminStore.isRemote(key));
  if (remoteDeps.length) {
    loading.value = true;
    try {
      await adminStore.loadRemoteAll(remoteDeps);
    } finally {
      loading.value = false;
    }
    await loadData();
  }
});
async function loadData() {
  loading.value = true;
  try {
    const result = await props.config.loadData({
      page: pagination.page ?? 1,
      pageSize: pagination.pageSize ?? 10,
      search
    });
    const lastPage = Math.max(1, Math.ceil(result.total / (pagination.pageSize ?? 10)));
    if ((pagination.page ?? 1) > lastPage) {
      pagination.page = lastPage;
      rows.value = result.data;
      pagination.itemCount = result.total;
      loadData();
      return;
    }
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
  if (field.type === 'multiple' || field.multiple) {
    return h(NSelect, {
      value: formModel[field.key] ?? [],
      options: toSelectOptions(typeof field.options === 'function' ? field.options() : (field.options ?? [])),
      clearable: true,
      filterable: true,
      multiple: true,
      maxTagCount: 2,
      placeholder: field.placeholder ?? '请选择（可多选）',
      'onUpdate:value': (v: any) => (formModel[field.key] = v)
    });
  }
  if (field.type === 'select') {
    return h(NSelect, {
      value: formModel[field.key],
      options: toSelectOptions(typeof field.options === 'function' ? field.options() : (field.options ?? [])),
      clearable: true,
      filterable: true,
      placeholder: field.placeholder,
      'onUpdate:value': (v: any) => (formModel[field.key] = v)
    });
  }
  if (field.type === 'image') {
    const current = formModel[field.key];
    return h(
      'div',
      { class: 'image-field' },
      [
        h(
          NUpload,
          {
            accept: 'image/*',
            multiple: false,
            max: 1,
            showFileList: false,
            defaultUpload: false,
            'onUpdate:fileList': (files: UploadFileInfo[]) => {
              const file = files[0]?.file;
              if (!file) return;
              const reader = new FileReader();
              reader.addEventListener('load', () => {
                formModel[field.key] = reader.result;
              });
              reader.readAsDataURL(file);
            }
          },
          {
            default: () =>
              current
                ? h(NImage, { src: current, width: 120, height: 120, objectFit: 'cover', style: 'border-radius:6px' })
                : h('div', { class: 'image-upload-trigger' }, '点击上传图片')
          }
        ),
        current
          ? h(
              NButton,
              {
                size: 'tiny',
                quaternary: true,
                style: 'margin-top:8px',
                onClick: () => {
                  formModel[field.key] = '';
                }
              },
              { default: () => '移除图片' }
            )
          : null
      ]
    );
  }
  if (field.type === 'date') {
    const raw = formModel[field.key];
    const ts = raw ? new Date(String(raw).replace(' ', 'T')).getTime() : null;
    return h(NDatePicker, {
      value: ts,
      type: 'date',
      clearable: true,
      placeholder: field.placeholder ?? '请选择日期',
      'onUpdate:value': (v: any) => {
        if (!v) {
          formModel[field.key] = '';
          return;
        }
        const d = new Date(v);
        const p = (n: number) => String(n).padStart(2, '0');
        formModel[field.key] = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
      }
    });
  }
  if (field.type === 'textarea') {
    return h(NInput, {
      value: formModel[field.key] ?? '',
      type: 'textarea',
      rows: 4,
      placeholder: field.placeholder,
      'onUpdate:value': (v: string) => (formModel[field.key] = v)
    });
  }
  if (field.type === 'geocode') {
    const lat = formModel[field.geocodeLatKey ?? 'latitude'];
    const lng = formModel[field.geocodeLngKey ?? 'longitude'];
    const btn = h(
      NButton,
      {
        size: 'small',
        type: 'info',
        onClick: async () => {
          const addr = formModel[field.geocodeSourceKey ?? 'address'];
          if (!addr || !String(addr).trim()) {
            window.$message?.warning('请先填写详细地址');
            return;
          }
          const result = await geocodeAddress(String(addr).trim());
          if (!result) {
            window.$message?.error('地址解析失败，请检查地址是否正确（密钥由服务端配置）');
            return;
          }
          formModel[field.geocodeLatKey ?? 'latitude'] = result.latitude;
          formModel[field.geocodeLngKey ?? 'longitude'] = result.longitude;
          window.$message?.success('坐标解析成功');
        }
      },
      { default: () => '按地址解析经纬度' }
    );
    const hint = lat != null && lng != null
      ? h('span', { style: 'margin-left:12px;color:#9B9B96;font-size:12px' }, `${lat}, ${lng}`)
      : null;
    return h('div', { style: 'display:flex;align-items:center;flex-wrap:wrap;gap:8px' }, [btn, hint]);
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

defineExpose({ reload: loadData });
</script>

<template>
  <div class="admin-list-page">
    <NCard :bordered="false" :title="config.title" class="card-wrapper">
      <template #header-extra>
        <NSpace wrap>
          <NButton
            v-for="(action, index) in config.toolbar ?? []"
            :key="index"
            size="small"
            :type="action.type ?? 'default'"
            @click="handleAction(action, null)"
          >
            {{ action.label }}
          </NButton>
          <NButton
            v-if="config.importConfig"
            size="small"
            @click="openImport"
          >
            导入
          </NButton>

          <NPopover v-model:show="columnSettingsVisible" trigger="click" placement="bottom-end" :width="220">
          <template #trigger>
            <NButton size="small" quaternary>列设置</NButton>
          </template>
          <div class="column-settings">
            <div class="column-settings__head">
              <NCheckbox
                :checked="hiddenColumns.length === 0"
                :indeterminate="hiddenColumns.length > 0 && hiddenColumns.length < columnOptions.length"
                @update:checked="toggleAllColumns"
              >
                全选
              </NCheckbox>
            </div>
            <div class="column-settings__list">
                <NCheckbox
                  v-for="opt in columnOptions"
                  :key="opt.value"
                  :checked="!hiddenColumns.includes(opt.value)"
                  @update:checked="(checked: boolean) => {
                    if (checked) {
                      hiddenColumns = hiddenColumns.filter(k => k !== opt.value);
                    } else {
                      hiddenColumns = [...hiddenColumns, opt.value];
                    }
                  }"
                >
                  {{ opt.label }}
                </NCheckbox>
              </div>
          </div>
        </NPopover>
      </NSpace>
      </template>

      <NForm v-if="config.searchFields?.length" class="mb-12px">
        <div class="search-grid">
          <div v-for="field in config.searchFields" :key="field.key" class="search-item">
            <label class="search-label">{{ field.label }}</label>
            <NInput
              v-if="!field.type || field.type === 'input'"
              v-model:value="search[field.key]"
              :placeholder="field.placeholder"
              clearable
              @keyup.enter="handleSearch"
            />
            <NSelect
              v-else
              v-model:value="search[field.key]"
              :options="toSelectOptions(typeof field.options === 'function' ? field.options() : (field.options ?? []))"
              clearable
              filterable
            />
          </div>
        </div>
        <div class="mt-12px">
          <NButton size="small" type="primary" @click="handleSearch">查询</NButton>
          <NButton size="small" class="ml-8px" @click="handleReset">重置</NButton>
        </div>
      </NForm>

      <NDataTable
        remote
        :columns="columns"
        :data="rows"
        :scroll-x="scrollX"
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
        <div class="flex flex-wrap justify-end gap-12px">
          <NButton @click="modalVisible = false">取消</NButton>
          <NButton type="primary" @click="submitModal">确定</NButton>
        </div>
      </NForm>
    </NModal>

    <NModal v-model:show="reasonVisible" preset="dialog" :title="reasonTitle" class="w-480px">
      <NInput v-model:value="reasonText" type="textarea" :rows="4" placeholder="请填写备注信息（必填）" />
      <div class="mt-16px flex flex-wrap justify-end gap-12px">
        <NButton @click="reasonVisible = false">取消</NButton>
        <NButton type="primary" @click="confirmReason">确定</NButton>
      </div>
    </NModal>

    <NModal v-model:show="groupSelectVisible" preset="card" title="请选择绑定对象" class="w-480px">
      <NSelect
        v-model:value="groupPickedValue"
        :options="groupSelectOptions"
        clearable
        filterable
        placeholder="选择类型后选择具体对象"
      />
      <div class="mt-16px flex flex-wrap justify-end gap-12px">
        <NButton @click="groupSelectVisible = false">取消</NButton>
        <NButton type="primary" @click="confirmGroupPicker">确定</NButton>
      </div>
    </NModal>

    <NModal v-model:show="cascadeVisible" preset="card" :title="cascadeTitle" class="w-480px">
      <NSpace vertical>
        <NSelect
          v-model:value="cascadeFirstValue"
          :options="cascadeFirstOptions"
          clearable
          filterable
          placeholder="请选择经营者类型"
          @update:value="onCascadeFirstChange"
        />
        <NSelect
          v-model:value="cascadeSecondValue"
          :options="cascadeSecondOptions"
          clearable
          filterable
          placeholder="请选择具体主体"
          :disabled="!cascadeFirstValue"
        />
      </NSpace>
      <div class="mt-16px flex flex-wrap justify-end gap-12px">
        <NButton @click="cascadeVisible = false">取消</NButton>
        <NButton type="primary" @click="confirmCascadePicker">确定</NButton>
      </div>
    </NModal>


    <NModal v-model:show="importVisible" preset="card" :title="config.importConfig?.title ?? '导入'" class="w-720px">
      <NSpace vertical>
        <NSpace align="center" wrap>
          <NButton size="small" @click="downloadTemplate">下载模板</NButton>
          <NUpload :show-file-list="false" accept=".xlsx,.xls,.csv" @change="handleImportFile">
            <NButton size="small" type="primary">选择文件上传</NButton>
          </NUpload>
          <span v-if="importFileName" style="font-size: 13px; color: #666;">{{ importFileName }}</span>
        </NSpace>
        <div v-if="importErrors.length" style="max-height: 160px; overflow: auto;">
          <div v-for="(e, i) in importErrors" :key="i" style="color: #e65a5a; font-size: 13px; line-height: 1.6;">{{ e }}</div>
        </div>
        <div v-if="importPreview.length" style="font-size: 13px; color: #333;">
          可导入 {{ importPreview.length }} 条
        </div>
        <div class="flex flex-wrap justify-end gap-12px">
          <NButton @click="importVisible = false">取消</NButton>
          <NButton type="primary" :disabled="!importPreview.length" @click="confirmImport">确认导入</NButton>
        </div>
      </NSpace>
    </NModal>
    <NModal v-model:show="pickerVisible" preset="card" :title="pickerTitle" class="w-480px">
      <NSelect v-model:value="pickedValue" :options="pickerOptions" clearable placeholder="请选择" filterable />
      <div class="mt-16px flex flex-wrap justify-end gap-12px">
        <NButton @click="pickerVisible = false">取消</NButton>
        <NButton type="primary" @click="confirmPicker">确定</NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.search-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px 16px;
}
.search-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.search-label {
  font-size: 13px;
  color: var(--n-text-color, #333);
}
.search-item :deep(.n-input),
.search-item :deep(.n-base-selection) {
  width: 100%;
}
@media (max-width: 1200px) {
  .search-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 900px) {
  .search-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.image-field {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.image-upload-trigger {
  width: 120px;
  height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  color: #9b9b96;
  cursor: pointer;
  font-size: 13px;
}
.column-settings__head {
  padding-bottom: 8px;
  margin-bottom: 8px;
  border-bottom: 1px solid #f0f0f0;
}
.column-settings__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 300px;
  overflow-y: auto;
}
</style>

