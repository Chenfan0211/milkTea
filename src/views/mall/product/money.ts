/** 接口金额单位为分，运营后台统一按元展示和录入。 */
export function fenToYuan(value?: number | null): number {
  return Number(((Number(value) || 0) / 100).toFixed(2));
}

export function yuanToFen(value?: number | string | null): number {
  return Math.round((Number(value) || 0) * 100);
}
