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
    availableStock: Math.max(0, Number(item.availableStock) || 0),
    cartLineId: String(item.cartLineId || `legacy:${item.skuId}`),
    addedQuantity: Math.max(0, Number(item.addedQuantity) || 0)
  });
}

function readState(userId) {
  try {
    const value = wx.getStorageSync(keyFor(userId));
    // 兼容原来只保存商品数组的购物车；商品和结算记录放在同一次本地写入中。
    if (Array.isArray(value)) return { items: value.map(normalize), settlements: {} };
    return {
      items: value && Array.isArray(value.items) ? value.items.map(normalize) : [],
      settlements: (value && value.settlements) || {}
    };
  } catch (error) {
    return { items: [], settlements: {} };
  }
}

function saveState(state, userId) {
  try {
    wx.setStorageSync(keyFor(userId), state);
  } catch (error) {
    /* storage failure keeps current page usable */
  }
  return state.items;
}

function read(userId) {
  return readState(userId).items;
}

function write(items, userId) {
  const state = readState(userId);
  const list = (Array.isArray(items) ? items : []).map(item => {
    const previous = state.items.find(row => Number(row.skuId) === Number(item.skuId));
    const next = normalize(item);
    next.cartLineId = previous ? previous.cartLineId : `line:${Date.now()}:${Math.random().toString(36).slice(2)}`;
    next.addedQuantity = previous ? previous.addedQuantity + Math.max(0, next.quantity - previous.quantity) : 0;
    return next;
  });
  saveState({ items: list, settlements: state.settlements }, userId);
  return list;
}

function rememberSettlement(orderNo, items, userId) {
  if (!orderNo || !userId) return;
  const state = readState(userId);
  if (state.settlements[orderNo]) return;
  state.settlements[orderNo] = {
    settled: false,
    items: (Array.isArray(items) ? items : []).map(item => ({
      skuId: Number(item.skuId),
      quantity: Number(item.quantity) || 0,
      cartLineId: item.cartLineId,
      addedQuantity: Number(item.addedQuantity) || 0
    }))
  };
  saveState(state, userId);
}

function settlePaidOrder(orderNo, userId) {
  if (!orderNo || !userId) return;
  const state = readState(userId);
  const settlement = state.settlements[orderNo];
  if (!settlement || settlement.settled) return;
  state.items = state.items.reduce((remaining, item) => {
    const snapshot = settlement.items.find(row => row.skuId === item.skuId && row.cartLineId === item.cartLineId);
    if (!snapshot) return remaining.concat(item);
    const addedLater = Math.max(0, item.addedQuantity - snapshot.addedQuantity);
    const quantity = item.quantity - Math.min(snapshot.quantity, Math.max(0, item.quantity - addedLater));
    if (quantity > 0) remaining.push(Object.assign({}, item, { quantity }));
    return remaining;
  }, []);
  settlement.settled = true;
  settlement.items = [];
  saveState(state, userId);
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

module.exports = { add, keyFor, read, remove, replace, summary, update, write, rememberSettlement, settlePaidOrder };
