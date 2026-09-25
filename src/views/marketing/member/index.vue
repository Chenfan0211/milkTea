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

/**
 * 会员等级。
 *
 * 字段口径对齐 member_level 表（真实列名）：
 *   level_code=等级、name=名称、amount_target=门槛(分)、discount=折扣、benefits=权益(JSON)、sort=排序
 *
 * 历史问题：
 *  1) 页原用 `level`，库中列是 `level_code` -> 静默丢弃；
 *  2) 页原用 `condition`（升级条件），member_level **根本没有该列**，故移除该字段；
 *  3) `benefits` 在库中是 [{icon,text,count}] 结构，原实现把它当纯字符串数组读写，
 *     会让权益的图标与数量丢失，故改为结构化编辑。
 */

/** 权益编辑弹窗（结构化：每条含图标、文案、可选数量） */
const benefitsVisible = ref(false);
const benefitsRow = ref<any>(null);
const benefitsList = ref<{ icon: string; text: string; count: string }[]>([]);

function openBenefitsEditor(row: any) {
  benefitsRow.value = row;
  const raw = Array.isArray(row.benefits) ? row.benefits : [];
  benefitsList.value = raw.map((b: any) =>
    typeof b === 'string'
      ? { icon: '', text: String(b), count: '' }
      : { icon: b?.icon ?? '', text: b?.text ?? '', count: b?.count ?? '' }
  );
  if (!benefitsList.value.length) benefitsList.value = [{ icon: "", text: "", count: "" }] as any;
  benefitsVisible.value = true;
}

function addBenefitRow() {
  benefitsList.value.push({ icon: '', text: '', count: '' });
}

function removeBenefitRow(i: number) {
  benefitsList.value.splice(i, 1);
}

async function saveBenefits() {
  if (!benefitsRow.value) return;
  // 过滤空行，保证写入的是干净结构
  const benefits = benefitsList.value
    .filter(b => String(b.text ?? '').trim())
    .map(b => ({ icon: b.icon ?? '', text: b.text.trim(), count: b.count ?? '' }));
  try {
    await store.update('memberLevels', benefitsRow.value.id, { benefits }, '营销中心', 'name');
    benefitsVisible.value = false;
    window.$message?.success('权益已保存');
    listRef.value?.reload();
  } catch (e: any) {
    window.$message?.error(e?.message || '保存失败');
  }
}

/** 分 -> 元展示（amount_target 单位为分） */
function fenToYuan(fen: any): string {
  const n = Number(fen);
  if (!Number.isFinite(n)) return "0.00";
  return (n / 100).toFixed(2);
}

const columns: DataTableColumns<any> = [
  { title: '等级', key: 'levelCode', width: 80 },
  { title: '名称', key: 'name', width: 120 },
  { title: '门槛(元)', key: 'amountTarget', width: 100, align: 'right', render: (row: any) => fenToYuan(row.amountTarget) },
  { title: '折扣', key: 'discount', width: 80 },
  {
    title: '权益',
    key: 'benefits',
    minWidth: 240,
    render: (row: any) =>
      (Array.isArray(row.benefits) ? row.benefits : [])
        .map((b: any) => (typeof b === 'string' ? b : b?.text))
        .filter(Boolean)
        .join('、') || '—'
  }
];
const searchFields: SearchField[] = [{ key: 'name', label: '名称', placeholder: '等级名称' }];
const formFields: FormField[] = [
  { key: 'levelCode', label: '等级', rules: [{ required: true, message: '请输入等级编码', trigger: ['input', 'blur'] }] },
  { key: 'name', label: '名称', rules: [{ required: true, message: '请输入名称', trigger: ['input', 'blur'] }] },
  { key: 'amountTarget', label: '门槛(元)', type: 'number', placeholder: '按元填写，保存时自动换算为分' },
  { key: 'discount', label: '折扣', placeholder: '如 8折 / 7折' }
];
const toolbar: RowAction[] = [{ label: '新增等级', type: 'primary', modal: 'add' }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  { label: '权益', type: 'info', handler: (row: any) => openBenefitsEditor(row) },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该等级？（请填写备注）',
    handler: async (row, reason) => await store.remove('memberLevels', row.id, '营销中心', 'name', reason)
  }
];
const config: AdminListConfig = {
  title: '会员等级',
  remoteKey: 'memberLevels',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('memberLevels', search, page, pageSize),
  form: {
    title: '会员等级',
    fields: formFields,
    /** 编辑回填：分 -> 元（表单按元编辑） */
    toFormData: (row: any) => ({
      ...row,
      amountTarget: row.amountTarget == null ? 0 : Number(row.amountTarget) / 100
    }),
    onSubmit: async (data, editing) => {
      // 元 -> 分；benefits 不在本表单维护（走「权益」按钮），编辑时保留原值
      const payload = {
        ...data,
        amountTarget: Math.round((Number(data.amountTarget) || 0) * 100)
      };
      if (editing) await store.update('memberLevels', editing.id, payload, '营销中心', 'name');
      else await store.add('memberLevels', { ...payload, benefits: [] }, '营销中心', 'name');
    }
  }
};
</script>

<template>
  <div class="page-root">

  <AdminListPage ref="listRef" :config="config" />

  <NModal v-model:show="benefitsVisible" preset="card" title="权益配置" class="w-720px">
    <div v-if="benefitsRow">
      <div class="benefits-name">等级：{{ benefitsRow.name }}（{{ benefitsRow.levelCode }}）</div>
      <div v-for="(b, i) in benefitsList" :key="i" class="benefit-row">
        <NInput v-model:value="b.icon" placeholder="图标名（可空，如 badge-percent）" class="bf-icon" />
        <NInput v-model:value="b.text" placeholder="权益文案（必填）" class="bf-text" />
        <NInput v-model:value="b.count" placeholder="数量（可空）" class="bf-count" />
        <NButton size="small" type="error" quaternary @click="removeBenefitRow(i)">删除</NButton>
      </div>
      <div class="mt-12px">
        <NButton size="small" @click="addBenefitRow">新增一条权益</NButton>
      </div>
      <div class="mt-8px text-12px color-#9B9B96">
        图标名对应小程序端图标；数量留空表示不显示数字（如「专属优惠券 3」的 3）。
      </div>
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
.benefit-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.bf-icon { width: 200px; }
.bf-text { flex: 1; }
.bf-count { width: 110px; }
</style>
