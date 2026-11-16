import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const mall = require('../packageMall/utils/mall.js');

const card = { id: 9, minPrice: 1000, minCustomerPrice: 1100, availableStock: 2 };
assert.equal(mall.normalizeProduct(card, false).priceText, '¥11.00');
assert.equal(mall.normalizeProduct(card, true).priceText, '¥10.00');

const detail = {
  id: 9,
  minPrice: 1000,
  availableStock: 2,
  skus: [{ id: 1, salePrice: 1000, distributionCommission: 100, availableStock: 2 }]
};
assert.equal(mall.normalizeDetail(detail, false).skus[0].salePriceText, '¥11.00');
assert.equal(mall.normalizeDetail(detail, true).skus[0].salePriceText, '¥10.00');
assert.equal(mall.normalizeDetail(detail, false).priceText, '¥11.00');
assert.equal(mall.normalizeDetail(detail, true).priceText, '¥10.00');
