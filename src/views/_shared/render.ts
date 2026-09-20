import { h } from 'vue';
import { NTag } from 'naive-ui';
import type { TagType } from './types';

type StatusMap = Record<string, { label: string; type?: TagType }>;

export function statusMap(def: Record<string, [string, TagType?]>): StatusMap {
  const result: StatusMap = {};
  for (const [key, value] of Object.entries(def)) {
    result[key] = { label: value[0], type: value[1] };
  }
  return result;
}

export function formatFen(fen?: number | null): string {
  if (fen == null) return '—';
  return (fen / 100).toFixed(2);
}

export function renderTag(key: string, map: StatusMap) {
  return (row: any) => {
    const value = row[key] as string;
    const item = map[value] ?? { label: value || '—' };
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
