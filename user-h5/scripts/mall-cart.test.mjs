import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

const storage = new Map();
global.wx = {
  getStorageSync(key) {
    return storage.has(key) ? storage.get(key) : '';
  },
  setStorageSync(key, value) {
    storage.set(key, value);
  }
};

const cart = require('../utils/mall-cart.js');
const mall = require('../packageMall/utils/mall.js');
const item = {
  skuId: 101,
  productId: 11,
  name: '青瓷茶杯',
  specValue: '500ml',
  image: '/cup.jpg',
  salePriceFen: 9900,
  quantity: 1,
  selected: true,
  listed: true,
  availableStock: 5
};

cart.add(item, 'u1');
cart.add(Object.assign({}, item, { quantity: 2 }), 'u1');
assert.equal(cart.read('u1').length, 1, '同一 SKU 必须合并');
assert.equal(cart.read('u1')[0].quantity, 3, '同一 SKU 数量必须累加');
assert.equal(cart.read('u2').length, 0, '不同用户购物车必须隔离');

cart.update(101, { quantity: 2 }, 'u1');
let summary = cart.summary(cart.read('u1'));
assert.equal(summary.count, 2, '汇总只计算选中且可购买数量');
assert.equal(summary.amountFen, 19800, '汇总金额按分计算');

cart.update(101, { quantity: 9, availableStock: 5 }, 'u1');
summary = cart.summary(cart.read('u1'));
assert.equal(summary.count, 0, '库存不足条目不参与结算汇总');

cart.add(Object.assign({}, item, { skuId: 102, quantity: 1, availableStock: 0 }), 'u1');
summary = cart.summary(cart.read('u1'));
assert.equal(summary.count, 0, '售罄商品不得参与结算');

assert.equal(
  mall.quotePriceChanged({ salePriceFen: 1000 }, 1100),
  false,
  '首次加购时，非团长的分销佣金不得误判为商品改价'
);
assert.equal(mall.quotePriceChanged({ unitPrice: 1100 }, 1100), false, '最终单价未变化时不得提示改价');
assert.equal(mall.quotePriceChanged({ unitPrice: 1100 }, 1200), true, '最终单价变化时必须提示改价');

console.log('mall-cart.test.mjs: passed');
