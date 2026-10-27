const auth = require('./auth');

const KEY_PREFIX = 'mallCart:v1:';

function keyFor(userId) {
  return KEY_PREFIX + String(userId || auth.getUserId() || 'anonymous');
}

function normalize(item) {
  return Object.assign({}, item, {
    skuId: Number(item.skuId),
    productId: Number(item.productId),
    quantity: Math.max(1, Math.min(99, Number(item.quantity) || 1)),
    selected: item.selected !== false,
    availableStock: Math.max(0, Number(item.availableStock) || 0)
  });
}

function read(userId) {
  try {
    const value = wx.getStorageSync(keyFor(userId));
    return Array.isArray(value) ? value.map(normalize) : [];
  } catch (error) {
    return [];
  }
}

function write(items, userId) {
  const list = (Array.isArray(items) ? items : []).map(normalize);
  try {
    wx.setStorageSync(keyFor(userId), list);
  } catch (error) {
    /* storage failure keeps current page usable */
  }
  return list;
}

function add(item, userId) {
  const list = read(userId);
  const index = list.findIndex(entry => Number(entry.skuId) === Number(item.skuId));
  if (index >= 0) {
    list[index] = normalize(
      Object.assign({}, list[index], item, {
        quantity: list[index].quantity + (Number(item.quantity) || 1)
      })
    );
  } else {
    list.push(normalize(item));
  }
  return write(list, userId);
}

function update(skuId, patch, userId) {
  return write(
    read(userId).map(item => (Number(item.skuId) === Number(skuId) ? normalize(Object.assign({}, item, patch)) : item)),
    userId
  );
}

function remove(skuIds, userId) {
  const ids = new Set((Array.isArray(skuIds) ? skuIds : [skuIds]).map(String));
  return write(
    read(userId).filter(item => !ids.has(String(item.skuId))),
    userId
  );
}

function replace(items, userId) {
  return write(items, userId);
}

function summary(items) {
  const list = (Array.isArray(items) ? items : []).map(normalize);
  return list.reduce(
    (result, item) => {
      if (!item.selected || !item.listed || item.availableStock < item.quantity) return result;
      result.count += item.quantity;
      result.amountFen += Number(item.salePriceFen) * item.quantity;
      return result;
    },
    { count: 0, amountFen: 0 }
  );
}

module.exports = { add, keyFor, read, remove, replace, summary, update, write };
