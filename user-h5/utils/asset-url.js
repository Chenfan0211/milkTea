/**
 * 资源地址归一化（纯函数，无 wx 依赖）。
 *
 * 规则：/assets 本地资源保持相对路径；/api/ 开头的相对地址补 BASE_URL；
 * 完整地址与本地临时文件路径原样返回。
 */
const config = require('../config');

function appendImageVariant(raw, variant) {
  if (!variant || !/\/api\/v1\/files\/public\/[^?#]+\.(?:jpe?g|png)(?:[?#]|$)/i.test(raw)) {
    return raw;
  }
  if (/[?&]variant=[^&#]*/i.test(raw)) return raw;
  const queryAt = raw.indexOf('?');
  const hashAt = raw.indexOf('#');
  if (queryAt >= 0 && (hashAt < 0 || queryAt < hashAt)) {
    return `${raw.slice(0, queryAt + 1)}variant=${encodeURIComponent(variant)}&${raw.slice(queryAt + 1)}`;
  }
  const insertAt = hashAt >= 0 ? hashAt : raw.length;
  return `${raw.slice(0, insertAt)}?variant=${encodeURIComponent(variant)}${raw.slice(insertAt)}`;
}

function resolveAssetUrl(value, variant) {
  const raw = value === undefined || value === null ? '' : String(value).trim();
  if (!raw) return '';
  if (/^(data|blob|wxfile|file):/i.test(raw) || /^\/\//.test(raw)) return raw;
  if (/^https?:/i.test(raw)) return appendImageVariant(raw, variant);
  if (raw.indexOf('/api/') === 0) {
    const base = String(config.BASE_URL || '').replace(/\/+$/, '');
    return appendImageVariant(base ? base + raw : raw, variant);
  }
  return raw;
}

module.exports = { resolveAssetUrl };
