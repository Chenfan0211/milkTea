import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const { parseDiscount, formatDiscountText } = require(path.join(root, 'utils/member-level.js'));

assert.equal(parseDiscount('80'), 0.8, '80 表示支付原价的 80%');
assert.equal(parseDiscount('70%'), 0.7, '70% 表示支付原价的 70%');
assert.equal(parseDiscount('1'), 0.01, '1 表示支付原价的 1%');
assert.equal(parseDiscount('100%'), 1, '100% 表示不打折');
assert.equal(parseDiscount('8折'), 0.8, '历史中文折扣继续兼容');
assert.equal(parseDiscount('0.8'), 0.8, '历史小数比例继续兼容');
for (const value of ['0', '-1', '101', '101%', 'abc', '', null]) {
  assert.equal(parseDiscount(value), 1, `${value} 应按不打折兜底`);
}

assert.equal(formatDiscountText('80'), '80%', '新规范展示为百分比');
assert.equal(formatDiscountText('8折'), '80%', '历史中文折扣统一展示为百分比');
assert.equal(formatDiscountText('0'), '无折扣', '无效折扣不展示百分比');

console.log('会员折扣百分比解析测试通过');