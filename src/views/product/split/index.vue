<script setup lang="ts">

defineOptions({
  name: 'product_split'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

/**
 * 分账规则。
 *
 * 口径说明（重要）：
 * 数据库 split_rule 存的是**万分比**（0~10000，五方合计必须 = 10000），
 * 不是「每件固定金额」。故页面统一按「比例」展示：
 *   展示值 = 万分比 / 100，例如 store_ratio=5000 -> 50%
 *
 * 历史问题：原页面列名写成「门店/件(元)」并读取 storePerItem / channelPerItem /
 * investorPercent，但后端返回的是 storeRatio / channelRatio / investorRatio，
 * 字段名对不上导致三列恒为空。此处已改为直接读取库中字段。
 *
 * scope 取值为 GLOBAL / PRODUCT（大写英文），需做中文映射。
 */

/** 作用范围：数据库取值 -> 中文 */
const SCOPE_LABELS: Record<string, string> = {
  GLOBAL: '全局',
  PRODUCT: '商品'
};

/** 作用范围下拉（value 必须用数据库原值，否则查询条件匹配不到） */
const SCOPE_OPTIONS = [
  { label: '全局', value: 'GLOBAL' },
  { label: '商品', value: 'PRODUCT' }
];

/** 万分比 -> 百分比展示（5000 -> 50%），保留两位小数去掉多余的 0 */
function toPercent(ratio: any): string {
  const n = Number(ratio);
  if (!Number.isFinite(n)) return '0%';
  const percent = n / 100;
  return `${Number.isInteger(percent) ? percent : percent.toFixed(2)}%`;
}

/** 分 -> 元展示（阈值金额列用） */
function fenToYuan(fen: any): string {
  const n = Number(fen);
  if (!Number.isFinite(n) || n === 0) return '—';
  return `¥${(n / 100).toFixed(2)}`;
}

const columns: DataTableColumns<any> = [
  { title: '规则编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  {
    title: '作用范围',
    key: 'scope',
    width: 100,
    render: (row: any) => SCOPE_LABELS[String(row.scope)] ?? row.scope ?? '—'
  },
  { title: '门店比例', key: 'storeRatio', width: 100, align: 'right', render: (row: any) => toPercent(row.storeRatio) },
  { title: '资源方比例', key: 'channelRatio', width: 110, align: 'right', render: (row: any) => toPercent(row.channelRatio) },
  { title: '投资人比例', key: 'investorRatio', width: 110, align: 'right', render: (row: any) => toPercent(row.investorRatio) },
  {
    title: '投资人达标额',
    key: 'investorThresholdAmount',
    width: 120,
    align: 'right',
    render: (row: any) => fenToYuan(row.investorThresholdAmount)
  },
  {
    title: '达标后比例',
    key: 'investorRatioAfter',
    width: 110,
    align: 'right',
    // 阈值为 0 表示不启用该规则，此时显示 —，避免与「投资人比例」重复造成误解
    render: (row: any) => (Number(row.investorThresholdAmount) > 0 ? toPercent(row.investorRatioAfter) : '—')
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag('status', statusMap({ enabled: ['启用', 'success'], disabled: ['停用', 'default'] }))
  }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '规则', placeholder: '规则名称' },
  {
    // eq_ 前缀 -> 后端按等值过滤（scope 已在 CrudRegistry 登记为 filterable）
    key: 'eq_scope',
    label: '范围',
    type: 'select',
    options: SCOPE_OPTIONS
  }
];

const formFields: FormField[] = [
  { key: 'code', label: '编码', rules: [{ required: true, message: '请输入编码', trigger: ['input', 'blur'] }] },
  { key: 'name', label: '名称', rules: [{ required: true, message: '请输入名称', trigger: ['input', 'blur'] }] },
  {
    key: 'scope',
    label: '范围',
    type: 'select',
    options: SCOPE_OPTIONS,
    placeholder: '请选择作用范围',
    rules: [{ required: true, message: '请选择作用范围', trigger: ['change', 'blur'] }]
  },
  { key: 'platformRatio', label: '平台比例(万分比)', type: 'number', placeholder: '如 1000 = 10%' },
  { key: 'storeRatio', label: '门店比例(万分比)', type: 'number', placeholder: '如 5000 = 50%' },
  { key: 'channelRatio', label: '资源方比例(万分比)', type: 'number', placeholder: '如 1500 = 15%' },
  { key: 'investorRatio', label: '投资人比例(万分比)', type: 'number', placeholder: '如 1500 = 15%' },
  { key: 'supplierRatio', label: '供应商比例(万分比)', type: 'number', placeholder: '如 1000 = 10%' },
  {
    key: 'investorThresholdAmount',
    label: '达标额(分)',
    type: 'number',
    placeholder: '0 = 不启用；如 100000 = 1000 元'
  },
  {
    key: 'investorRatioAfter',
    label: '达标后比例(万分比)',
    type: 'number',
    placeholder: '投资人达标后启用的比例'
  }
];

const toolbar: RowAction[] = [{ label: '新增分账规则', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该规则？（请填写备注）',
    // 统一走 patch 写库：原实现调用 store.enableSplitRule()，只改本地镜像不落库，
    // 刷新后状态回退（同页「停用」走 patch 却是真写库，两者行为不一致）。
    handler: async (row, reason) =>
      await store.patch('splitRules', row.id, { status: 'enabled' }, '商品中心', '启用规则', 'name', reason),
    visible: row => row.status === 'disabled'
  },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该规则？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('splitRules', row.id, { status: 'disabled' }, '商品中心', '停用规则', 'name', reason),
    visible: row => row.status === 'enabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该规则？（请填写备注）',
    handler: async (row, reason) => await store.remove('splitRules', row.id, '商品中心', 'name', reason)
  }
];

/**
 * 校验五方比例合计。
 * 与后端 SplitCalculator 校验口径一致（totalRatio != 10000 抛错），
 * 前端提前拦截可避免提交后才收到「分账比例合计必须为 10000」的报错。
 */
function validateRatios(data: Record<string, any>) {
  const keys = ['platformRatio', 'storeRatio', 'channelRatio', 'investorRatio', 'supplierRatio'];
  const total = keys.reduce((sum, key) => sum + (Number(data[key]) || 0), 0);
  if (total !== 10000) {
    throw new Error(`五方比例合计必须为 10000（万分比），当前为 ${total}`);
  }
  validateInvestorThreshold(data);
}

/**
 * 校验「投资人当月达标后比例」配置的合法性。
 *
 * <p>达标后比例提升的部分由平台让出（平台 = 原平台 - 增量），故增量不能超过原平台比例，
 * 否则平台比例会变成负数 —— 等于平台倒贴钱。
 * 后端 SplitCalculator 会直接抛错，这里提前拦截以给出更明确的中文提示。
 */
function validateInvestorThreshold(data: Record<string, any>) {
  const threshold = Number(data.investorThresholdAmount) || 0;
  const after = Number(data.investorRatioAfter) || 0;
  const base = Number(data.investorRatio) || 0;
  const platform = Number(data.platformRatio) || 0;

  // 阈值为 0 表示不启用；达标比例为 0 表示未配置 —— 都不参与校验
  if (threshold <= 0 || after <= 0) return;
  if (after <= base) return;

  const delta = after - base;
  if (delta > platform) {
    throw new Error(
      `达标后投资人比例比原比例高 ${delta}（万分比），超出平台比例 ${platform}，会导致平台比例变负。` +
        '请提高平台比例，或降低达标后投资人比例。'
    );
  }
}

const config: AdminListConfig = {
  title: '分账规则',
  remoteKey: 'splitRules',
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('splitRules', search, page, pageSize),
  form: {
    title: '分账规则',
    fields: formFields,
    /** 编辑回填：比例与阈值归一为数字，避免 NInputNumber 值类型不匹配 */
    toFormData: (row: any) => ({
      ...row,
      platformRatio: Number(row.platformRatio) || 0,
      storeRatio: Number(row.storeRatio) || 0,
      channelRatio: Number(row.channelRatio) || 0,
      investorRatio: Number(row.investorRatio) || 0,
      supplierRatio: Number(row.supplierRatio) || 0,
      investorThresholdAmount: Number(row.investorThresholdAmount) || 0,
      investorRatioAfter: Number(row.investorRatioAfter) || 0
    }),
    onSubmit: async (data, editing) => {
      const payload = {
        ...data,
        platformRatio: Number(data.platformRatio) || 0,
        storeRatio: Number(data.storeRatio) || 0,
        channelRatio: Number(data.channelRatio) || 0,
        investorRatio: Number(data.investorRatio) || 0,
        supplierRatio: Number(data.supplierRatio) || 0,
        investorThresholdAmount: Number(data.investorThresholdAmount) || 0,
        investorRatioAfter: Number(data.investorRatioAfter) || 0
      };
      validateRatios(payload);
      if (editing) await store.update('splitRules', editing.id, payload, '商品中心', 'name');
      else await store.add('splitRules', { ...payload, status: 'disabled' }, '商品中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
