import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const { ratioToPercent, fenToYuanText, normalizeCommission } = require(path.join(root, 'utils/commission.js'));

assert.equal(ratioToPercent(1500), '15%', 'base ratio');
assert.equal(ratioToPercent(2000), '20%', 'after ratio');
assert.equal(ratioToPercent(0), '0%', 'unset ratio');
assert.equal(fenToYuanText(100000), '¥1000.00', 'fen to yuan');
assert.equal(fenToYuanText(68000), '¥680.00', 'fen to yuan 2');

const normalized = normalizeCommission({
  month: '2026-10',
  rule: { investorRatio: 1500, investorThresholdAmount: 100000, investorRatioAfter: 2000, thresholdEnabled: true },
  current: {
    commissionAmount: 68000,
    salesAmount: 890000,
    settledAmount: 80000,
    pendingAmount: 43456,
    effectiveRatio: 1500,
    reached: false
  },
  progress: { percent: 68, remainToThreshold: 32000 },
  stores: [{ id: 1, name: '星沙店', salesAmount: 520000, commissionAmount: 78000, orderCount: 12 }],
  records: [{ month: '2026-10', commissionAmount: 123456, salesAmount: 890000, effectiveRatio: 2000, reached: true }]
});
assert.equal(normalized.current.commissionText, '¥680.00', 'month commission');
assert.equal(normalized.rule.basePercent, '15%', 'base pct');
assert.equal(normalized.rule.afterPercent, '20%', 'after pct');
assert.equal(normalized.progress.percent, 68, 'progress pct');
assert.equal(normalized.progress.remainText, '¥320.00', 'remain');
assert.equal(normalized.stores[0].salesText, '¥5200.00', 'store sales');
assert.equal(normalized.records[0].reached, true, 'record reached');

const empty = normalizeCommission(null);
assert.deepEqual(empty.stores, [], 'empty stores');
assert.equal(empty.current.commissionText, '¥0.00', 'empty amount');
assert.equal(empty.progress.percent, 0, 'empty progress');

for (const ext of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(path.join(root, 'packageRole/role-commission/role-commission.' + ext)), 'missing ' + ext);
}
const wxml = fs.readFileSync(path.join(root, 'packageRole/role-commission/role-commission.wxml'), 'utf8');
const js = fs.readFileSync(path.join(root, 'packageRole/role-commission/role-commission.js'), 'utf8');
const wxss = fs.readFileSync(path.join(root, 'packageRole/role-commission/role-commission.wxss'), 'utf8');

assert.ok(wxml.includes('commission-progress__fill'), 'must render progress fill');
assert.ok(wxml.includes('view.progress.percent'), 'progress width driven by percent');
assert.ok(wxml.includes('view.progress.reached'), 'must distinguish reached');
assert.ok(wxml.includes('门店销售额') && wxml.includes('近一年分佣'), 'must have store sales and yearly records');
assert.ok(js.includes('fetchInvestorCommission'), 'must call fetchInvestorCommission');
assert.ok(!/#[0-9A-Fa-f]{3,8}\b/.test(wxss), 'must use design tokens only');

console.log('月度分佣：进度条、达标口径、门店汇总与近一年记录测试通过');
