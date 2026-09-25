// 客户端固定枚举 / 本地状态兜底。
//
// 说明（阶段 C 假数据清理）：
// - 业务数据（门店 / 菜单 / 商品 / 订单 / 优惠券 / 储值 / 礼品卡 / 积分 /
//   会员等级 / 城市 / 运营配置）已全部迁移到后端数据库，
//   由 /api/v1/app/** 接口提供，见 server/src/main/resources/db/migration/V16__seed_app_data.sql。
// - 本文件只保留「与服务端无关」的客户端内容：
//   · 页签 / 分类等固定枚举（后端不返回或仅作 UI 展示）
//   · 纯客户端的本地状态（购物车初始值）
//   · 与后端数据结构无关的格式化工具函数
// - 新增业务数据请改数据库 seed 或运营后台，不要往本文件加。

/**
 * 金额展示：整数不带小数位，小数保留一位。
 *
 * 入参必须是「元」。后端下发的是「分」，调用方需先换算。
 * 对 null / undefined / 非数字 / NaN 统一返回 '0'，避免历史数据缺字段时抛异常。
 */
function formatOrderAmount(amount) {
  const value = Number(amount);
  if (!Number.isFinite(value)) return '0';
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

/** 订单页分类页签（纯 UI 枚举）。 */
const orderCategories = [
  { id: 'all', label: '全部订单' },
  { id: 'store', label: '门店订单' },
  { id: 'stored-value', label: '储值订单' },
  { id: 'gift-card', label: '礼品卡订单' }
];

/** 积分商城分类页签（纯 UI 枚举，商品由接口按 category 过滤）。 */
const pointsCategories = [
  { id: 'all', label: '全部' },
  { id: 'pet', label: '宠物公益专区' },
  { id: 'coupon', label: '优惠券区' }
];

/** 兑换记录状态页签（纯 UI 枚举）。 */
const exchangeRecordCategories = [
  { id: 'all', label: '全部' },
  { id: 'pending_payment', label: '待支付' },
  { id: 'pending_delivery', label: '待发货' },
  { id: 'pending_receipt', label: '待收货' },
  { id: 'pending_verify', label: '待核销' },
  { id: 'verified', label: '已核销' },
  { id: 'completed', label: '已完成' }
];

/** 门店类型本地兜底字典；正常情况下读取 /api/v1/app/store-types。 */
const storeTypes = [
  { id: 1, code: 'convenience', name: '便利店', sort: 1, enabled: true },
  { id: 2, code: 'restaurant', name: '餐饮', sort: 2, enabled: true },
  { id: 3, code: 'gym', name: '健身房', sort: 3, enabled: true },
  { id: 4, code: 'milk-tea', name: '奶茶/饮品', sort: 4, enabled: true }
];

module.exports = {
  formatOrderAmount,
  orderCategories,
  pointsCategories,
  exchangeRecordCategories,
  storeTypes
};
