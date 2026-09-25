import { h } from 'vue';
import { NTag } from 'naive-ui';
import type { TagType } from './types';

type StatusMap = Record<string, { label: string; type?: TagType }>;

/**
 * 通用枚举中文兜底字典。
 *
 * 背景：后端各表 status 值命名不统一（active/enabled/INACTIVE/CREATED...），
 * 页面局部 statusMap 漏配时，renderTag 会把英文原样显示（如营业状态列出现 "active"）。
 * 这里集中维护「常见枚举值 -> 中文」，页面专属映射优先，未命中再走这份兜底。
 */
export const COMMON_STATUS_LABELS: Record<string, string> = {
  // 通用启用/停用
  active: '启用',
  inactive: '停用',
  enabled: '启用',
  disabled: '停用',
  // 签约/审核
  signed: '已签约',
  unsigned: '未签约',
  pending: '待审核',
  approved: '已通过',
  rejected: '已驳回',
  resolved: '已处理',
  revoked: '已撤销',
  // 布尔类
  true: '是',
  false: '否',
  on: '开启',
  off: '关闭',
  valid: '有效',
  invalid: '无效',
  ready: '就绪',
  incomplete: '未完成',
  // 订单/支付状态
  CREATED: '待支付',
  UNPAID: '待支付',
  PAID: '已支付',
  VERIFIED: '已核销',
  COMPLETED: '已完成',
  CANCELED: '已取消',
  REFUNDED: '已退款',
  PAYING: '支付中',
  CLOSED: '已关闭',
  FAILED: '失败',
  SUCCESS: '成功',
  PROCESSING: '处理中',
  DUPLICATE_PAY: '重复支付',
  // 提现/审核状态
  APPLIED: '待审核',
  AUDITING: '审核中',
  // 结算/流水
  SETTLE: '结算',
  // 对账异常类型
  MISSING_SPLIT: '缺失分账快照',
  MISSING_REVERSE: '退款未冲正',
  SPLIT_AMOUNT_MISMATCH: '分账金额不一致',
  // 核销类型
  ORDER: '订单',
  EXCHANGE: '兑换',
  // 营业状态
  open: '营业中',
  closed: '停业'
};

export function statusMap(def: Record<string, [string, TagType?]>): StatusMap {
  const result: StatusMap = {};
  for (const [key, value] of Object.entries(def)) {
    result[key] = { label: value[0], type: value[1] };
  }
  return result;
}

export function formatFen(fen?: number | null): string {
  // 金额为空/无账户时统一显示 0.00（用户约定：余额没值显示 0）
  if (fen == null) return '0.00';
  return (fen / 100).toFixed(2);
}

/**
 * 日期展示工具：统一格式化为 `yyyy-MM-dd HH:mm:ss`。
 *
 * 支持输入：Date 实例 / 后端 `yyyy-MM-dd HH:mm:ss` 字符串 / ISO 字符串 / 时间戳。
 * 空值返回 '—'（避免 Invalid Date 与英文残留）。
 */
const DATE_FULL_PATTERN = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/;
const DATE_ONLY_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

function parseDateValue(value: any): Date | null {
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  if (typeof value === 'number') {
    const d = new Date(value);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  const text = String(value ?? '').trim();
  if (!text) return null;
  const full = text.match(DATE_FULL_PATTERN);
  if (full) {
    return new Date(
      Number(full[1]), Number(full[2]) - 1, Number(full[3]),
      Number(full[4]), Number(full[5]), Number(full[6])
    );
  }
  const only = text.match(DATE_ONLY_PATTERN);
  if (only) {
    return new Date(Number(only[1]), Number(only[2]) - 1, Number(only[3]));
  }
  const d = new Date(text);
  return Number.isNaN(d.getTime()) ? null : d;
}

const pad2 = (n: number) => String(n).padStart(2, '0');

/** 完整时间：yyyy-MM-dd HH:mm:ss（空值返回 '—'） */
export function formatDateTime(value: any): string {
  const d = parseDateValue(value);
  if (!d) return value == null || value === '' ? '—' : String(value);
  return (
    `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ` +
    `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
  );
}

/** 仅日期：yyyy-MM-dd（空值返回 '—'） */
export function formatDate(value: any): string {
  const d = parseDateValue(value);
  if (!d) return value == null || value === '' ? '—' : String(value);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** 供 DataTable 时间列使用的 render（完整时间） */
export function renderDateTime(key: string) {
  return (row: any) => formatDateTime(row[key]);
}

/** 供 DataTable 日期列使用的 render（仅日期） */
export function renderDate(key: string) {
  return (row: any) => formatDate(row[key]);
}

function lookupCaseInsensitive(map: Record<string, { label: string; type?: TagType }>, value: string) {
  if (map[value]) return map[value];
  const upper = value.toUpperCase();
  for (const key of Object.keys(map)) {
    if (key.toUpperCase() === upper) return map[key];
  }
  return undefined;
}

function lookupLabelCaseInsensitive(map: Record<string, string>, value: string) {
  if (map[value]) return map[value];
  const upper = value.toUpperCase();
  for (const key of Object.keys(map)) {
    if (key.toUpperCase() === upper) return map[key];
  }
  return undefined;
}

export function renderTag(key: string, map: StatusMap) {
  return (row: any) => {
    const raw = row[key];
    const value = raw == null ? '' : String(raw);
    // 查找顺序：页面专属映射 -> 通用兜底 -> 原值（映射与兜底均大小写不敏感）
    const commonLabel = lookupLabelCaseInsensitive(COMMON_STATUS_LABELS, value);
    const hit: { label: string; type?: TagType } | undefined =
      lookupCaseInsensitive(map, value) ?? (commonLabel ? { label: commonLabel } : undefined);
    const item = hit ?? { label: value || '—' };
    return h(NTag, { size: 'small', bordered: false, type: item.type ?? 'default' }, { default: () => item.label });
  };
}

export function renderMoney(key: string) {
  return (row: any) => formatFen(row[key] as number | null);
}

export function notReady(action?: string) {
  const prefix = action ? `${action}：` : '';
  window.$message?.info(`${prefix}后端接口未接入，当前为演示数据`);
}
