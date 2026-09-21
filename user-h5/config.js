// 小程序运行配置：后端接口地址与 Mock 开关。
// 接真实后端时，将 USE_MOCK 置为 false 并填写 BASE_URL 即可切换。
module.exports = {
  // 是否使用本地 Mock 响应（后端未就绪时为 true）
  USE_MOCK: true,
  // 后端服务基础地址（接入真实后端后填写，如 https://api.example.com）
  BASE_URL: '',
  // 门店类型字典缓存有效期（毫秒），默认 1 小时
  STORE_TYPE_CACHE_TTL: 60 * 60 * 1000
};