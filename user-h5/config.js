// 小程序运行配置：后端接口地址与 Mock 开关。
//
// 当前阶段：备案已通过，但 HTTPS 证书尚未配置，因此
//   · 开发者工具：用「IP + 8089 预览入口」联调（需勾选「不校验合法域名」）；
//   · 真机 / 上线：必须使用 HTTPS 域名 https://api.wulingshiguang.top
//     （微信要求 request 合法域名 + HTTPS，IP 与非标端口在真机上不可用）。
//
// 切换方式（无需改代码）：
//   · 构建期注入：MINI_BASE_URL=https://api.wulingshiguang.top
//   · 或直接修改下方 defaultBaseUrl
const env = (typeof process !== 'undefined' && process.env) || {};

// 开发/演示用预览入口（HTTP + IP + 非标端口，仅开发者工具勾选「不校验域名」后可用）
const PREVIEW_BASE_URL = 'http://43.136.91.239:8089';
// 生产入口（HTTPS 域名，需在微信公众平台配置为 request 合法域名）
const PRODUCTION_BASE_URL = 'https://api.wulingshiguang.top';

module.exports = {
  // 是否使用本地 Mock 响应。阶段 C 后 mock 兜底已移除，业务数据全部来自后端，
  // 因此默认关闭 Mock，直接请求后端。
  USE_MOCK: env.MINI_USE_MOCK ? env.MINI_USE_MOCK !== 'false' : false,
  // 后端服务基础地址：默认走预览入口；上线时注入 MINI_BASE_URL 切换为 HTTPS 域名。
  BASE_URL: env.MINI_BASE_URL || PREVIEW_BASE_URL,
  PREVIEW_BASE_URL,
  PRODUCTION_BASE_URL,
  // 门店类型字典缓存有效期（毫秒），默认 1 小时
  STORE_TYPE_CACHE_TTL: 60 * 60 * 1000
};
