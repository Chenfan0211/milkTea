// 门店联系电话：取门店数据中的真实字段，便于用户直接拨打。
// 门店来自 /api/v1/app/stores，页面在 onLoad 已刷新镜像。
//
// 说明（假数据清理）：
// 客服热线 / 服务时段 / 常见问题原先在本文件硬编码，与后台
// app_config.service_info、app_config.service_faqs 重复维护，
// 且热线为占位号码。现已全部改为读取后台配置，本文件不再保留客服文案。
const { resolveStoreCatalog } = require('../utils/store');

function getServiceStores() {
  const catalog = resolveStoreCatalog();
  return (catalog.stores || []).map(store => ({
    id: store.id,
    name: store.name,
    phone: store.phone,
    businessHours: store.businessHours
  }));
}

module.exports = {
  getServiceStores
};
