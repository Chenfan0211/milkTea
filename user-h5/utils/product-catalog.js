// 运营后台商品池：与 milkTea 管理端 src/store/modules/admin/index.ts 的
// productId / onSale 字段保持一致。onSale === 'off' 表示平台级下架，
// 门店选品与消费端点单页都不应再展示该商品。
const PLATFORM_PRODUCTS = [
  { productId: 'classic-001', onSale: 'on' },
  { productId: 'classic-005', onSale: 'on' },
  { productId: 'classic-002', onSale: 'off' },
  { productId: 'classic-003', onSale: 'on' },
  { productId: 'classic-004', onSale: 'on' },
  { productId: 'featured-001', onSale: 'on' }
];

function getPlatformProductIds() {
  return PLATFORM_PRODUCTS.map(item => item.productId);
}

// 运营后台已上架的商品 ID 列表。
function getPlatformListedIds() {
  return PLATFORM_PRODUCTS.filter(item => item.onSale === 'on').map(item => item.productId);
}

// 运营后台已完成下架的商品 ID 列表。
function getPlatformUnlistedIds() {
  return PLATFORM_PRODUCTS.filter(item => item.onSale !== 'on').map(item => item.productId);
}

// 商品是否在运营后台可售；未纳入后台池的商品视为不可选。
function isPlatformListed(productId) {
  const entry = PLATFORM_PRODUCTS.find(item => item.productId === productId);
  return Boolean(entry) && entry.onSale === 'on';
}

module.exports = {
  PLATFORM_PRODUCTS,
  getPlatformListedIds,
  getPlatformProductIds,
  getPlatformUnlistedIds,
  isPlatformListed
};