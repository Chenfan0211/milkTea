<script setup lang="ts">
defineOptions({
  name: 'marketing_coupon'
});

import { h } from 'vue';
import { NImage, NTag } from 'naive-ui';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction, FormField } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';
import { renderTag, statusMap } from '@/views/_shared/render';

const store = useAdminStore();

/**
 * 优惠券管理。
 *
 * 字段口径对齐 coupon 表（真实列名）：
 *   name=券标题、threshold=使用门槛(分)、stock=发放数量、
 *   validity_type/validity_start/validity_end=有效期、channel/payment_restriction=新增列
 *
 * 历史问题（本页最严重）：
 *  原表单用 title / condition / quantity / expiryText / validityPeriod / channel /
 *  paymentRestriction，其中 title/condition/quantity 与库中列完全不符，
 *  提交时直接触发 500 报错（非静默丢弃），优惠券新增/编辑实际不可用。
 *
 * 有效期口径（用户确认）：改为**日期区间**，即 validity_start ~ validity_end，
 *  便于筛选与到期判断；不再使用旧的自由文本 expiryText。
 */

/** 金额：分 -> 元 */
function fenToYuan(fen: any): string {
  const n = Number(fen);
  if (!Number.isFinite(n) || n === 0) return '—';
  return (n / 100).toFixed(2);
}

/// 日期补时间：开始日 00:00:00
function toDayStart(v: any): string | null {
  const s = String(v ?? '').trim();
  if (!s) return null;
  return s.length >= 10 ? `${s.slice(0, 10)} 00:00:00` : s;
}

/// 日期补时间：结束日 23:59:59（含当日）
function toDayEnd(v: any): string | null {
  const s = String(v ?? '').trim();
  if (!s) return null;
  return s.length >= 10 ? `${s.slice(0, 10)} 23:59:59` : s;
}

/** 有效期展示：区间 或 天数 */
function validityText(row: any): string {
  if (row.validityType === 'days') {
    return row.validityDays ? `领券后 ${row.validityDays} 天` : '—';
  }
  const s = row.validityStart,
    e = row.validityEnd;
  if (!s && !e) return '—';
  return `${String(s).slice(0, 10)} ~ ${String(e).slice(0, 10)}`;
}

const columns: DataTableColumns<any> = [
  {
    title: '券面图',
    key: 'image',
    width: 80,
    render: (row: any) =>
      row.image
        ? h(NImage, { src: row.image, width: 56, height: 40, objectFit: 'cover', style: 'border-radius:4px' })
        : h('span', { style: 'color:#9B9B96' }, '—')
  },
  { title: '券标题', key: 'name', minWidth: 200 },
  {
    title: '类型',
    key: 'type',
    width: 90,
    render: (row: any) => (row.type === 'voucher' ? '代金券' : row.type === 'discount' ? '折扣券' : row.type || '—')
  },
  { title: '面额(元)', key: 'amount', width: 90, align: 'right', render: (row: any) => fenToYuan(row.amount) },
  {
    title: '使用门槛(元)',
    key: 'threshold',
    width: 110,
    align: 'right',
    render: (row: any) => fenToYuan(row.threshold)
  },
  { title: '品牌', key: 'brand', width: 100, render: (row: any) => row.brand || '—' },
  { title: '使用场景', key: 'scenes', minWidth: 140, render: (row: any) => row.scenes || '—' },
  { title: '有效期', key: 'validityStart', width: 190, render: (row: any) => validityText(row) },
  { title: '发放数量', key: 'stock', width: 100, align: 'right' },
  { title: '渠道', key: 'channel', width: 100, render: (row: any) => row.channel || '—' },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: renderTag('status', statusMap({ enabled: ['启用', 'success'], disabled: ['停用', 'default'] }))
  }
];

const searchFields: SearchField[] = [
  { key: 'name', label: '券标题', placeholder: '券标题' },
  {
    key: 'eq_status',
    label: '状态',
    type: 'select',
    options: [
      { label: '启用', value: 'enabled' },
      { label: '停用', value: 'disabled' }
    ]
  }
];

const formFields: FormField[] = [
  { key: 'image', label: '券面图', type: 'image' },
  { key: 'name', label: '券标题', rules: [{ required: true, message: '请输入券标题', trigger: ['input', 'blur'] }] },
  {
    key: 'type',
    label: '类型',
    type: 'select',
    options: [
      { label: '代金券', value: 'voucher' },
      { label: '折扣券', value: 'discount' }
    ]
  },
  { key: 'amount', label: '面额(元)', type: 'number', placeholder: '按元填写，保存时换算为分' },
  { key: 'threshold', label: '使用门槛(元)', type: 'number', placeholder: '满多少元可用，0=无门槛' },
  { key: 'brand', label: '品牌' },
  { key: 'scenes', label: '使用场景' },
  {
    key: 'validityType',
    label: '有效期方式',
    type: 'select',
    options: [
      { label: '固定日期区间', value: 'range' },
      { label: '领券后N天', value: 'days' }
    ]
  },
  { key: 'validityStart', label: '生效日期', type: 'date' },
  { key: 'validityEnd', label: '失效日期', type: 'date' },
  { key: 'validityDays', label: '领券后有效天数', type: 'number' },
  { key: 'usageTime', label: '使用时段', placeholder: '如 全天 / 10:00-22:00' },
  { key: 'channel', label: '渠道', placeholder: '如 线上 / 门店' },
  { key: 'paymentRestriction', label: '支付限制', placeholder: '如 仅微信支付' },
  { key: 'stock', label: '发放数量', type: 'number' },
  { key: 'source', label: '来源' },
  {
    key: 'applicableStoreIds',
    label: '适用门店',
    type: 'multiple',
    multiple: true,
    options: () =>
      store.subjects.filter((s: any) => s.type === 'store').map((s: any) => ({ label: s.name, value: s.code }))
  },
  {
    key: 'applicableProductIds',
    label: '适用商品',
    type: 'multiple',
    multiple: true,
    options: () => store.products.map((p: any) => ({ label: p.name, value: p.productId }))
  },
  { key: 'description', label: '使用说明', type: 'textarea' }
];

const toolbar: RowAction[] = [{ label: '新增优惠券', type: 'primary', modal: 'add' }];

const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', modal: 'edit' },
  {
    label: '停用',
    type: 'warning',
    reasonPrompt: '确认停用该优惠券？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('coupons', row.id, { status: 'disabled' }, '营销中心', '停用', 'name', reason),
    visible: row => row.status === 'enabled'
  },
  {
    label: '启用',
    type: 'success',
    reasonPrompt: '确认启用该优惠券？（请填写备注）',
    handler: async (row, reason) =>
      await store.patch('coupons', row.id, { status: 'enabled' }, '营销中心', '启用', 'name', reason),
    visible: row => row.status === 'disabled'
  },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该优惠券？（请填写备注）',
    handler: async (row, reason) => await store.remove('coupons', row.id, '营销中心', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '优惠券管理',
  remoteKey: 'coupons',
  // 「适用门店」下拉按 subjects 过滤，需预加载该资源
  remoteDeps: ['subjects'],
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('coupons', search, page, pageSize),
  form: {
    title: '优惠券',
    fields: formFields,
    /** 编辑回填：分 -> 元；日期型字段兜底为空串，避免 NDatePicker 值类型不匹配 */
    toFormData: (row: any) => ({
      ...row,
      amount: row.amount == null ? 0 : Number(row.amount) / 100,
      threshold: row.threshold == null ? 0 : Number(row.threshold) / 100,
      validityType: row.validityType || 'range',
      validityStart: row.validityStart || '',
      validityEnd: row.validityEnd || '',
      validityDays: row.validityDays == null ? 0 : Number(row.validityDays),
      stock: row.stock == null ? 0 : Number(row.stock),
      applicableStoreIds: Array.isArray(row.applicableStoreIds) ? row.applicableStoreIds : [],
      applicableProductIds: Array.isArray(row.applicableProductIds) ? row.applicableProductIds : []
    }),
    onSubmit: async (data, editing) => {
      // 元 -> 分；有效期按所选方式清理无关字段，避免残留脏数据
      const isRange = data.validityType !== 'days';
      const payload = {
        ...data,
        amount: Math.round((Number(data.amount) || 0) * 100),
        threshold: Math.round((Number(data.threshold) || 0) * 100),
        stock: Number(data.stock) || 0,
        validityType: isRange ? 'range' : 'days',
        // 库中为 DATETIME：生效当日 00:00:00 起，失效当日 23:59:59 止，
        // 保证「选到某天」时该天整日都有效，符合运营直觉。
        validityStart: isRange ? toDayStart(data.validityStart) : null,
        validityEnd: isRange ? toDayEnd(data.validityEnd) : null,
        validityDays: isRange ? 0 : Number(data.validityDays) || 0
      };
      // 日期区间模式下的基础校验
      if (
        isRange &&
        payload.validityStart &&
        payload.validityEnd &&
        String(payload.validityStart) > String(payload.validityEnd)
      ) {
        throw new Error('生效日期不能晚于失效日期');
      }
      if (editing) await store.update('coupons', editing.id, payload, '营销中心', 'name');
      else await store.add('coupons', { ...payload, status: 'enabled' }, '营销中心', 'name');
    }
  }
};
</script>

<template>
  <AdminListPage :config="config" />
</template>

<style scoped></style>
