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

module.exports = {
  buildCartId,
  mergeEditedCartItem,
  removeCartItems
};
