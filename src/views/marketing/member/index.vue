<script setup lang="ts">
defineOptions({
  name: 'marketing_member'
});

import { computed, reactive, ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import BenefitIconSelect from './BenefitIconSelect.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

interface BenefitRow {
  icon: string;
  text: string;
  count: string;
}

const editorVisible = ref(false);
const editorMode = ref<'add' | 'edit'>('add');
const editingId = ref<number | null>(null);
const editorTitle = computed(() => (editorMode.value === 'add' ? '新增会员等级' : '编辑会员等级'));
const benefits = ref<BenefitRow[]>([]);
const originalDiscount = ref('');
const discountWasInvalid = ref(false);

const form = reactive({
  levelCode: '',
  name: '',
  amountTarget: null as number | null,
  discount: null as number | null
});

function toBenefitRow(raw: any): BenefitRow {
  if (typeof raw === 'string') {
    return { icon: '', text: raw, count: '' };
  }
  return {
    icon: String(raw?.icon ?? ''),
    text: String(raw?.text ?? ''),
    count: raw?.count == null ? '' : String(raw.count)
  };
}

/** 兼容数组、JSON 字符串、普通字符串和旧 string[] 权益结构。 */
function parseBenefits(raw: any): BenefitRow[] {
  if (Array.isArray(raw)) return raw.map(toBenefitRow);
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed.map(toBenefitRow);
    } catch {
      return [{ icon: '', text: raw.trim(), count: '' }];
    }
  }
  return [];
}

/** 历史折扣 / 新百分比统一转换为后台整数百分比输入值。 */
function normalizeDiscountPercent(raw: any): number | null {
  if (raw == null) return null;
  const text = String(raw).trim();
  if (!text) return null;

  const zheMatch = text.match(/([-+]?\d+(?:\.\d+)?)\s*折/);
  if (zheMatch) {
    const zhe = Number(zheMatch[1]);
    return zhe > 0 && zhe <= 10 ? Math.round(zhe * 10) : null;
  }

  const percentMatch = text.match(/([-+]?\d+(?:\.\d+)?)\s*%/);
  if (percentMatch) {
    const percent = Number(percentMatch[1]);
    return percent > 0 && percent <= 100 ? Math.round(percent) : null;
  }

  const value = Number(text);
  if (!Number.isFinite(value) || value <= 0) return null;
  if (value > 0 && value < 1) return Math.round(value * 100);
  if (Number.isInteger(value) && value <= 100) return value;
  return null;
}

const benefitTextOptions = computed(() => {
  const options = store.dictEntries
    .filter((entry: any) => entry.dictType === 'member_benefit' && Number(entry.enabled) === 1)
    .map((entry: any) => ({
      label: String(entry.itemName ?? '').trim(),
      value: String(entry.itemName ?? '').trim(),
      sort: Number(entry.sort) || 0,
      id: Number(entry.id) || 0
    }))
    .filter((entry: any) => entry.value)
    .sort((a: any, b: any) => a.sort - b.sort || a.id - b.id);

  const seen = new Set<string>();
  return options.filter((entry: any) => {
    if (seen.has(entry.value)) return false;
    seen.add(entry.value);
    return true;
  }).map(({ label, value }: any) => ({ label, value }));
});

function benefitOptionsFor(text: string) {
  const current = String(text ?? '').trim();
  const options = benefitTextOptions.value;
  if (current && !options.some(option => option.value === current)) {
    return [{ label: current, value: current }, ...options];
  }
  return options;
}

function resetEditor() {
  form.levelCode = '';
  form.name = '';
  form.amountTarget = null;
  form.discount = null;
  benefits.value = [{ icon: '', text: '', count: '' }];
  editingId.value = null;
  originalDiscount.value = '';
  discountWasInvalid.value = false;
}

function openEditor(mode: 'add' | 'edit', row?: any) {
  resetEditor();
  editorMode.value = mode;
  if (mode === 'edit' && row) {
    editingId.value = Number(row.id);
    form.levelCode = String(row.levelCode ?? '');
    form.name = String(row.name ?? '');
    form.amountTarget = row.amountTarget == null ? null : Number(row.amountTarget) / 100;
    originalDiscount.value = String(row.discount ?? '');
    const normalizedDiscount = normalizeDiscountPercent(row.discount);
    form.discount = normalizedDiscount;
    discountWasInvalid.value = normalizedDiscount == null && Boolean(originalDiscount.value.trim());
    const parsed = parseBenefits(row.benefits);
    benefits.value = parsed.length ? parsed : [{ icon: '', text: '', count: '' }];
  }
  editorVisible.value = true;
}

function addBenefitRow() {
  benefits.value.push({ icon: '', text: '', count: '' });
}

function removeBenefitRow(index: number) {
  benefits.value.splice(index, 1);
}

function buildBenefits() {
  return benefits.value
    .filter(row => String(row.text ?? '').trim())
    .map(row => ({
      icon: String(row.icon ?? '').trim(),
      text: String(row.text ?? '').trim(),
      count: String(row.count ?? '').trim()
    }));
}

async function saveEditor() {
  const levelCode = form.levelCode.trim();
  const name = form.name.trim();
  if (!levelCode) {
    window.$message?.warning('请输入等级编码');
    return;
  }
  if (!name) {
    window.$message?.warning('请输入等级名称');
    return;
  }
  if (
    form.discount != null &&
    (!Number.isInteger(form.discount) || form.discount < 1 || form.discount > 100)
  ) {
    window.$message?.warning('折扣请输入 1-100 的整数百分比');
    return;
  }

  let discount = '';
  if (form.discount == null) {
    discount = editingId.value && discountWasInvalid.value ? originalDiscount.value : '';
  } else {
    discount = String(form.discount);
  }

  const amountTarget = Math.round((Number(form.amountTarget) || 0) * 100);
  const payload = {
    levelCode,
    name,
    amountTarget,
    discount,
    benefits: buildBenefits()
  };

  try {
    if (editingId.value) {
      await store.update('memberLevels', editingId.value, payload, '营销中心', 'name');
    } else {
      await store.add('memberLevels', payload, '营销中心', 'name');
    }
    editorVisible.value = false;
    window.$message?.success(editingId.value ? '会员等级已更新' : '会员等级已新增');
    await listRef.value?.reload();
  } catch (error: any) {
    window.$message?.error(error?.message || '保存失败');
  }
}

/** 分 -> 元展示（amount_target 单位为分） */
function fenToYuan(fen: any): string {
  const n = Number(fen);
  if (!Number.isFinite(n)) return '0.00';
  return (n / 100).toFixed(2);
}

const columns: DataTableColumns<any> = [
  { title: '等级', key: 'levelCode', width: 100 },
  { title: '名称', key: 'name', width: 140 },
  {
    title: '门槛(元)',
    key: 'amountTarget',
    width: 110,
    align: 'right',
    render: (row: any) => fenToYuan(row.amountTarget)
  },
  {
    title: '权益',
    key: 'benefits',
    minWidth: 260,
    render: (row: any) =>
      parseBenefits(row.benefits)
        .map(item => item.text)
        .filter(Boolean)
        .join('、') || '—'
  }
];

const searchFields: SearchField[] = [{ key: 'name', label: '名称', placeholder: '等级名称' }];
const toolbar: RowAction[] = [
  { label: '新增等级', type: 'primary', handler: () => openEditor('add') }
];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', handler: (row: any) => openEditor('edit', row) },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该等级？（请填写备注）',
    handler: async (row, reason) => await store.remove('memberLevels', row.id, '营销中心', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '会员等级',
  remoteDeps: ['dictEntries'],
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) =>
    store.queryRemote('memberLevels', search, page, pageSize)
};
</script>

<template>
  <div class="page-root">
    <AdminListPage ref="listRef" :config="config" />

    <NModal v-model:show="editorVisible" preset="card" :title="editorTitle" class="w-860px">
      <NForm label-placement="left" :label-width="96">
        <NFormItem label="等级">
          <NInput
            v-model:value="form.levelCode"
            :disabled="editorMode === 'edit'"
            placeholder="如 Lv1（编辑时不可修改）"
          />
        </NFormItem>

        <NFormItem label="名称">
          <NInput v-model:value="form.name" placeholder="如 时光卡" />
        </NFormItem>

        <NFormItem label="门槛(元)">
          <NInputNumber
            v-model:value="form.amountTarget"
            :min="0"
            :precision="2"
            placeholder="按元填写，保存时自动换算为分"
            class="w-240px"
          />
        </NFormItem>

        <NFormItem label="折扣">
          <div class="discount-input">
            <NInputNumber
              v-model:value="form.discount"
              :min="1"
              :max="100"
              :precision="0"
              :show-button="false"
              placeholder="如 80"
              class="w-160px"
            />
            <span class="discount-input__suffix">%</span>
            <span class="discount-input__hint">80 表示支付原价的 80%</span>
          </div>
        </NFormItem>

        <NFormItem label="权益">
          <div class="benefit-editor">
            <div class="benefit-editor__head">
              <span>图标</span>
              <span>权益文案</span>
              <span>数量</span>
              <span>操作</span>
            </div>

            <div v-for="(benefit, index) in benefits" :key="index" class="benefit-editor__row">
              <BenefitIconSelect v-model="benefit.icon" />
              <NSelect
                v-model:value="benefit.text"
                :options="benefitOptionsFor(benefit.text)"
                placeholder="选择权益文案"
                filterable
                clearable
              />
              <NInput v-model:value="benefit.count" placeholder="可空" />
              <NButton size="small" type="error" quaternary @click="removeBenefitRow(index)">
                删除
              </NButton>
            </div>

            <div v-if="!benefits.length" class="benefit-editor__empty">暂无权益，点击下方按钮添加</div>

            <div class="mt-8px">
              <NButton size="small" @click="addBenefitRow">新增一条权益</NButton>
            </div>
            <div class="benefit-editor__tip">
              权益文案来自数据字典 member_benefit；图标和数量可留空。
            </div>
          </div>
        </NFormItem>

        <div class="flex flex-wrap justify-end gap-12px mt-20px">
          <NButton @click="editorVisible = false">取消</NButton>
          <NButton type="primary" @click="saveEditor">保存</NButton>
        </div>
      </NForm>
    </NModal>
  </div>
</template>

<style scoped>
.discount-input {
  display: flex;
  align-items: center;
  gap: 8px;
}

.discount-input__suffix {
  color: #555;
  font-weight: 600;
}

.discount-input__hint {
  color: #8b8f86;
  font-size: 13px;
}

.benefit-editor {
  width: 100%;
}

.benefit-editor__head,
.benefit-editor__row {
  display: grid;
  grid-template-columns: 180px minmax(240px, 1fr) 120px 64px;
  gap: 8px;
  align-items: center;
}

.benefit-editor__head {
  margin-bottom: 6px;
  color: #6f746b;
  font-size: 13px;
}

.benefit-editor__row {
  margin-bottom: 8px;
}

.benefit-editor__empty {
  padding: 12px;
  border: 1px dashed #dcdfd7;
  border-radius: 6px;
  color: #8b8f86;
  font-size: 13px;
  text-align: center;
}

.benefit-editor__tip {
  margin-top: 8px;
  color: #8b8f86;
  font-size: 12px;
}
</style>