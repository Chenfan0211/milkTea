/**
 * 日期展示工具：统一把各种日期来源格式化为标准格式。
 *
 *   · 含时分秒 → `yyyy-MM-dd HH:mm:ss`（如 2026-09-23 09:22:06）
 *   · 仅日期   → `yyyy-MM-dd`       （如 2026-09-23）
 *
 * 页面按需选用：列表类通常只展示日期，详情/流水类展示完整时间。
 *
 * 支持输入：
 *   - Date 实例
 *   - 后端返回的 `yyyy-MM-dd HH:mm:ss` 字符串
 *   - 后端返回的 ISO 字符串 / 时间戳
 *   - '' / null / undefined（返回原值，避免页面显示 Invalid Date）
 */

const FULL_PATTERN = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/;
const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

const pad = value => String(value).padStart(2, '0');

/** 把后端 `yyyy-MM-dd HH:mm:ss` 字符串解析为本地 Date，避免时区偏移。 */
function parseDate(value) {
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  if (typeof value === 'number') {
    const fromNumber = new Date(value);
    return Number.isNaN(fromNumber.getTime()) ? null : fromNumber;
  }
  const text = String(value || '').trim();
  if (!text) return null;
  const matched = text.match(FULL_PATTERN);
  if (matched) {
    return new Date(
      Number(matched[1]), Number(matched[2]) - 1, Number(matched[3]),
      Number(matched[4]), Number(matched[5]), Number(matched[6])
    );
  }
  const dateOnly = text.match(DATE_PATTERN);
  if (dateOnly) {
    return new Date(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3]));
  }
  const parsed = new Date(text);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/** 完整时间：yyyy-MM-dd HH:mm:ss */
function formatDateTime(value) {
  const date = parseDate(value);
  if (!date) return value == null ? '' : String(value);
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/** 仅日期：yyyy-MM-dd */
function formatDate(value) {
  const date = parseDate(value);
  if (!date) return value == null ? '' : String(value);
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

module.exports = {
  formatDate,
  formatDateTime,
  parseDate
};
