// 小程序运行配置：后端接口地址与 Mock 开关。
//
// 默认走本地 Mock（后端未就绪 / 本地无合法域名时）。
// 接入真实后端（ICP 备案与 HTTPS 合法域名通过后）二选一：
//   1) 直接修改下方 USE_MOCK / BASE_URL；
//   2) 构建期注入环境变量（CI 一键切换，不落库）：
//        MINI_USE_MOCK=false  MINI_BASE_URL=https://api.example.com
//
// 注意：BASE_URL 需指向网关 HTTPS 域名；真实登录依赖 WX_APP_SECRET。
const env = (typeof process !== 'undefined' && process.env) || {};

module.exports = {
  // 是否使用本地 Mock 响应。阶段 C 后 mock 兜底已移除，业务数据全部来自后端，
  // 因此默认关闭 Mock，直接请求后端。
  USE_MOCK: env.MINI_USE_MOCK ? env.MINI_USE_MOCK !== 'false' : false,
  // 后端服务基础地址。开发者工具联调时需在「详情-本地设置」勾选
  // 「不校验合法域名」，否则非 HTTPS 地址会被拦截。
  BASE_URL: env.MINI_BASE_URL || 'http://43.136.91.239:8089',
  // 门店类型字典缓存有效期（毫秒），默认 1 小时
  STORE_TYPE_CACHE_TTL: 60 * 60 * 1000
};