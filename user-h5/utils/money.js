/**
 * 金额工具：全项目金额统一以「分」（整数）为内部计算单位，
 * 仅在展示时转换为「元」。避免浮点乘法/累加导致的精度丢失
 * （如 0.04 * 3 = 0.12000000000000001）。
 */

/** 元 -> 分（整数，四舍五入到最接近的分） */
function yuanToFen(yuan) {
  const n = Number(yuan);
  if (!Number.isFinite(n)) return 0;
  return Math.round(n * 100);
}

/** 分 -> 元（数字，保留到分） */
function fenToYuan(fen) {
  const n = Number(fen);
  if (!Number.isFinite(n)) return 0;
  return Math.round(n) / 100;
}

/**
 * 分 -> 展示字符串（方案 A：整数不带小数，非整数保留 2 位并去尾零）。
 * 例如：400 -> "4"，4 -> "0.04"，50 -> "0.5"，105 -> "1.05"
 */
function formatFen(fen) {
  const n = Math.round(Number(fen) || 0);
  if (n % 100 === 0) return String(n / 100);
  const yuan = (n / 100).toFixed(2);
  return yuan.endsWith('0') ? yuan.slice(0, -1) : yuan;
}

/**
 * 元 -> 展示字符串（方案 A）。兼容既有「元」入参的调用点，
 * 内部先转分再格式化，保证与 formatFen 行为一致。
 */
function formatYuan(yuan) {
  return formatFen(yuanToFen(yuan));
}

/** 整数分累加（避免浮点累加误差） */
function sumFen(amounts) {
  return (amounts || []).reduce((sum, value) => sum + yuanToFen(value), 0);
}

module.exports = {
  yuanToFen,
  fenToYuan,
  formatFen,
  formatYuan,
  sumFen
};
