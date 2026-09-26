import type { VNodeChild } from 'vue';

/** 详情弹层字段定义 */
export interface DetailField {
  label: string;
  key?: string;
  render?: (row: any) => VNodeChild | string | number;
}

/** 详情弹层分组定义 */
export interface DetailGroup {
  title?: string;
  fields: DetailField[];
}

/**
 * 详情加载器：给定行数据，返回详情所需的完整数据。
 *
 * 返回 null 表示数据不存在（弹层内展示空状态）。
 */
export type DetailLoader = (row: any) => Promise<any> | any;

export interface RowActionDetail {
  title?: string | ((row: any) => string);
  /** 详情分组（静态） */
  groups?: DetailGroup[] | ((row: any) => DetailGroup[]);
  /** 详情数据加载器；不配置则直接使用行数据 */
  load?: DetailLoader;
}
