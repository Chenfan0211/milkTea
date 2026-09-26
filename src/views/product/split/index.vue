<script setup lang="ts">

defineOptions({
  name: 'product_split'
});

import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';
import { toggleSplitRule } from '@/service/api/crud';

const store = useAdminStore();

/**
 * 分账规则（固定金额 + 成本直给模型）。
 *
 * 口径说明：
 * - store_ratio / channel_ratio：门店 / 资源方「每件提成」，固定金额（分），按商品件数计；
 * - investor_ratio / investor_ratio_after：投资人提成 / 达标后提成，百分比（万分比存库）；
 * - investor_threshold_amount：投资人当月累计达标额（分），必填；
 * - 供应商成本、平台提成从商品明细（cost_price / platform_commission）取，不在规则里配置；
 * - scope 仅支持 GLOBAL，商品维度规则后续再开发。
 */

/** 分 -> 元展示（金额列用） */
function fenToYuan(fen: any): string {
  const n = Number(fen);
  if (!Number.isFinite(n) || n === 0) return '¥0.00';
  return `¥${(n / 100).toFixed(2)}`;
}

/** 万分比 -> 百分比展示（5000 -> 50%） */
function toPercent(ratio: any): string {
  const n = Number(ratio);
  if (!Number.isFinite(n)) return '0%';
  const percent = n / 100;
  return `${Number.isInteger(percent) ? percent : percent.toFixed(2)}%`;
}

const columns: DataTableColumns<any> = [
  { title: '规则编码', key: 'code', width: 120 },
  { title: '名称', key: 'name', minWidth: 160 },
  {
    title: '作用范围',
    key: 'scope',
    width: 90,
    render: () => '全局'
  },
  {
    title: '门店每件提成',
    key: 'storeRatio',
    width: 120,
    align: 'right',
    render: (row: any) => fenToYuan(row.storeRatio)
  },
  {
    title: '资源方每件提成',
    key: 'channelRatio',
    width: 130,
    align: 'right',
    render: (row: any) => fenToYuan(row.channelRatio)
  },
  {
    title: '投资人比例',
    key: 'investorRatio',
    width: 110,
    align: 'right',
    render: (row: any) => toPercent(row.investorRatio)
  },
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
    render: (row: any) => toPercent(row.investorRatioAfter)
  },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: renderTag('status', statusMap({ enabled: ['启用', 'success'], disabled: ['停用', 'default'] }))
  }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '规则', placeholder: '规则名称' }
];

/** 百分比（如 15） -> 万分比（1500） */
function percentToBp(v: any): number {
  const n = Number(v);
  return Number.isFinite(n) ? Math.round(n * 100) : 0;
}

/** 万分比 -> 百分比（回填用） */
function bpToPercent(bp: any): number {
  const n = Number(bp);
  return Number.isFinite(n) ? n / 100 : 0;
}

/** 元 -> 分 */
function yuanToFen(v: any): number {
  const n = Number(v);
  return Number.isFinite(n) ? Math.round(n * 100) : 0;
}

/** 分 -> 元 */
function fenToYuanNum(fen: any): number {
  const n = Number(fen);
  return Number.isFinite(n) ? n / 100 : 0;
}

const formFields: FormField[] = [
  { key: 'code', label: '编码', rules: [{ required: true, message: '请输入编码', trigger: ['input', 'blur'] }] },
  { key: 'name', label: '名称', rules: [{ required: true, message: '请输入名称', trigger: ['input', 'blur'] }] },
  {
    key: 'storeRatio',
    label: '门店每件提成(元)',
    type: 'number',
    placeholder: '如 3.50 = 每件 3.5 元',
    rules: [{ required: true, message: '请输入门店每件提成', trigger: ['input', 'blur'] }]
  },
  {
    key: 'channelRatio',
    label: '资源方每件提成(元)',
    type: 'number',
    placeholder: '如 1.00 = 每件 1 元'
  },
  {
    key: 'investorRatio',
    label: '投资人比例(%)',
    type: 'number',
    placeholder: '如 15 = 15%',
    rules: [{ required: true, message: '请输入投资人比例', trigger: ['input', 'blur'] }]
  },
  {
    key: 'investorThresholdAmount',
    label: '投资人达标额(元)',
    type: 'number',
    placeholder: '必填，投资人当月累计分账达标额',
    rules: [{ required: true, message: '请输入投资人达标额', trigger: ['input', 'blur'] }]
  },
  {
    key: 'investorRatioAfter',
    label: '达标后比例(%)',
    type: 'number',
    placeholder: '达标后投资人提成百分比'
  }
];

const toolbar: RowAction[] = [{ label: '新增分账规则', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该规则？（请填写备注）',
    handler: async (row, reason) => await toggleSplitRule(row.id, true),
    visible: row => row.status === 'disabled'
  },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该规则？（请填写备注）',
    handler: async (row, reason) => await toggleSplitRule(row.id, false),
    visible: row => row.status === 'enabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该规则？（请填写备注）',
    handler: async (row, reason) => await store.remove('splitRules', row.id, '商品中心', 'name', reason)
  }
];

/** 校验投资人与达标后比例：达标后比例应不低于原比例，且均为 0~100 的百分比 */
function validateForm(data: Record<string, any>) {
  const investor = Number(data.investorRatio) || 0;
  const after = Number(data.investorRatioAfter) || 0;
  const threshold = Number(data.investorThresholdAmount) || 0;
  if (threshold <= 0) {
    throw new Error('投资人达标额必填且必须大于 0');
  }
  if (investor < 0 || investor > 100) {
    throw new Error('投资人比例必须在 0~100 之间');
  }
  if (after < 0 || after > 100) {
    throw new Error('达标后比例必须在 0~100 之间');
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
    /**
     * 编辑回填：只回填表单声明字段，金额/比例换算为「元 / 百分比」供表单展示。
     * 不再整行 {...row} 回填，避免 id/updateTime/deleted 等服务端字段进入提交 payload。
     */
    toFormData: (row: any) => ({
      code: row.code,
      name: row.name,
      storeRatio: fenToYuanNum(row.storeRatio),
      channelRatio: fenToYuanNum(row.channelRatio),
      investorRatio: bpToPercent(row.investorRatio),
      investorThresholdAmount: fenToYuanNum(row.investorThresholdAmount),
      investorRatioAfter: bpToPercent(row.investorRatioAfter)
    }),
    onSubmit: async (data, editing) => {
      validateForm(data);
      const payload = {
        code: data.code,
        name: data.name,
        scope: 'GLOBAL',
        storeRatio: yuanToFen(data.storeRatio),
        channelRatio: yuanToFen(data.channelRatio),
        investorRatio: percentToBp(data.investorRatio),
        investorThresholdAmount: yuanToFen(data.investorThresholdAmount),
        investorRatioAfter: percentToBp(data.investorRatioAfter)
      };
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
