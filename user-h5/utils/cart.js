function buildCartId(productId, selectedOptionIds = []) {
  return [productId, ...selectedOptionIds].filter(Boolean).join('-');
}

function mergeEditedCartItem(items, editedId, payload) {
  const editedIndex = items.findIndex(item => item.id === editedId);
  if (editedIndex === -1) return items.map(item => Object.assign({}, item));

  const edited = items[editedIndex];
  const selectedOptionIds = payload.selectedOptions.map(option => option.id);
  const nextId = buildCartId(payload.product.id, selectedOptionIds);
  const updated = Object.assign({}, edited, {
    id: nextId,
    productId: payload.product.id,
    selectedOptionIds,
    name: payload.product.name,
    spec: payload.specText,
    price: payload.unitPrice,
    originalPrice: payload.originalPrice,
    storedValuePrice: payload.storedValuePrice || 0,
    storedValueDiscount: payload.storedValueDiscount || 0,
    quantity: payload.quantity,
    image: payload.product.image
  });

  const duplicateIndex = items.findIndex((item, index) => index !== editedIndex && item.id === nextId);
  if (duplicateIndex === -1) {
    return items.map((item, index) => (index === editedIndex ? updated : Object.assign({}, item)));
  }

  const mergedItems = [];
  items.forEach((item, index) => {
    if (index === editedIndex) return;
    if (index === duplicateIndex) {
      mergedItems.push(
        Object.assign({}, item, {
          quantity: item.quantity + payload.quantity,
          selected: item.selected || updated.selected
        })
      );
      return;
    }
    mergedItems.push(Object.assign({}, item));
  });
  return mergedItems;
}

/**
 * 从购物车中移除本次已结算的条目（按条目 id 匹配，保留其余商品）。
 *
 * @param {Array} cartItems 购物车条目
 * @param {Array<string|number>} settledIds 本次已结算的条目 id 列表
 * @returns {Array} 移除后新的购物车条目（浅拷贝，不修改原数组）
 */
function removeCartItems(cartItems, settledIds) {
  const items = Array.isArray(cartItems) ? cartItems : [];
  const ids = new Set((settledIds || []).map(String));
  if (!ids.size) return items.map(item => Object.assign({}, item));
  return items.filter(item => !ids.has(String(item.id))).map(item => Object.assign({}, item));
}

/**
 * 由规格弹层 / 一键加购共用的购物车条目构造函数。
 * 调用方传入的金额单位统一为「元」，与购物车默认数据保持一致。
 *
 * @param {Object} payload
 * @param {Object} payload.product 商品（含 id/name/image）
 * @param {Array} payload.selectedOptions 已选规格选项
 * @param {number} payload.quantity 数量
 * @param {number} payload.unitPrice 会员单价（元）
 * @param {number} payload.originalPrice 原价（元）
 * @param {number} payload.storedValuePrice 储值价（元）
 * @param {number} payload.storedValueDiscount 储值立减（元）
 * @param {string} payload.specText 规格文案
 * @returns {Object} 购物车条目
 */
function buildCartItem(payload = {}) {
  const product = payload.product || {};
  const selectedOptions = payload.selectedOptions || [];
  const selectedOptionIds = selectedOptions.map(option => option.id);
  const item = {
    id: buildCartId(product.id, selectedOptionIds),
    productId: product.id,
    selectedOptionIds,
    name: product.name,
    spec: payload.specText || '',
    price: payload.unitPrice,
    originalPrice: payload.originalPrice,
    storedValuePrice: payload.storedValuePrice || 0,
    storedValueDiscount: payload.storedValueDiscount || 0,
    quantity: Math.max(1, Number(payload.quantity) || 1),
    selected: true,
    listed: true,
    image: product.image
  };
  if (payload.priceMode) item.priceMode = String(payload.priceMode);
  return item;
}

/**
 * 合并一条购物车条目：同 id（商品 + 规格）累加数量，否则追加。
 * 返回浅拷贝数组，不修改入参。
 */
function mergeCartItem(items, item) {
  const list = (items || []).map(entry => Object.assign({}, entry));
  const existing = list.find(entry => entry.id === item.id);
  if (existing) {
    existing.quantity += item.quantity;
    existing.selected = Boolean(existing.selected || item.selected);
    existing.listed = item.listed;
    return list;
  }
  list.push(Object.assign({}, item));
  return list;
}

/**
 * 按新门店的可售商品集合刷新购物车条目的 listed 状态；
 * 本店已下架的条目只取消勾选、不删除，避免阻塞结算。
 */
function applyStoreListing(cartItems, listedProductIds) {
  const listed = listedProductIds instanceof Set ? listedProductIds : new Set((listedProductIds || []).map(String));
  return (cartItems || []).map(item => {
    const isListed = listed.has(String(item.productId));
    return Object.assign({}, item, {
      listed: isListed,
      selected: isListed ? item.selected : false
    });
  });
}

module.exports = {
  applyStoreListing,
  buildCartId,
  buildCartItem,
  mergeCartItem,
  mergeEditedCartItem,
  removeCartItems
};
