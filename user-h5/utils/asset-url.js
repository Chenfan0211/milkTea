/**
 * 资源地址归一化（纯函数，无 wx 依赖）。
 *
 * 规则：/assets 本地资源保持相对路径；/api/ 开头的相对地址补 BASE_URL；
 * 完整地址与本地临时文件路径原样返回。
 */
const config = require('../config');

function resolveAssetUrl(value) {
  const raw = value === undefined || value === null ? '' : String(value).trim();
  if (!raw) return '';
  if (/^(data|blob|wxfile|file):/i.test(raw) || /^\/\//.test(raw)) return raw;
  if (/^https?:/i.test(raw)) return raw;
  if (raw.indexOf('/api/') === 0) {
    const base = String(config.BASE_URL || '').replace(/\/+$/, '');
    return base ? base + raw : raw;
  }
  return raw;
}

module.exports = { resolveAssetUrl };
