import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pageRoot = path.join(root, 'pages/stored-value-records/stored-value-records');

for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${pageRoot}.${extension}`), `缺少储值记录页文件: stored-value-records.${extension}`);
}

const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(appJson.pages.includes('pages/stored-value-records/stored-value-records'), 'app.json 必须注册储值记录页');
assert.ok(
  !appJson.tabBar.list.some(item => item.pagePath === 'pages/stored-value-records/stored-value-records'),
  '储值记录页不得加入 TabBar'
);

const wxml = fs.readFileSync(`${pageRoot}.wxml`, 'utf8');
const wxss = fs.readFileSync(`${pageRoot}.wxss`, 'utf8');
const js = fs.readFileSync(`${pageRoot}.js`, 'utf8');

assert.ok(wxml.includes('title="储值记录"') && wxml.includes('back="{{true}}"'), '储值记录页必须提供标题与返回');
assert.ok(wxml.includes('empty-state') && wxml.includes('receipt-muted.svg'), '储值记录页必须复用空态组件并引用 Lucide 图标');
assert.ok(wxml.includes('账户余额（元）'), '储值记录页必须展示账户余额');
assert.ok(js.includes('fetchStoredValueRecords'), '储值记录页必须调用储值流水接口');
assert.ok(js.includes('onReachBottom'), '储值记录页必须支持上拉加载更多');

// 设计 token 约束
assert.ok(
  wxss.includes('var(--page-gutter)') && wxss.includes('var(--brand-green)') && wxss.includes('var(--radius-lg)'),
  '储值记录页样式必须引用设计 token'
);
const colorLiterals = [...wxss.matchAll(/#[0-9A-Fa-f]{3,8}\b/g)]
  .map(m => m[0])
  .filter(color => color.toUpperCase() !== '#FFFFFF');
assert.deepEqual(colorLiterals, [], '储值记录页 WXSS 不得写死设计系统外的颜色');
assert.ok(!/rgba?\(/.test(wxss), '储值记录页 WXSS 不得写死 rgba 颜色');
assert.ok(!/\b\d+px\b/.test(wxss), '储值记录页 WXSS 不得使用 px');

const spacingDeclarations = [...wxss.matchAll(/(?:margin|padding)(?:-[a-z]+)?\s*:\s*([^;]+)/g)];
const spacingValues = spacingDeclarations.flatMap(m =>
  [...m[1].matchAll(/(-?\d+)rpx/g)].map(v => Number(v[1]))
);
const invalidSpacing = [...new Set(spacingValues.filter(v => ![4, 8, 12, 16, 20, 24, 32, 40].includes(v)))];
assert.deepEqual(invalidSpacing, [], '储值记录页 margin/padding 只能使用设计系统八档间距');

const fontSizeLiterals = [...wxss.matchAll(/font-size:\s*(\d+rpx)/g)].map(m => m[1]);
assert.deepEqual(fontSizeLiterals, [], '储值记录页字号必须引用设计 token');

// 入口关联：会员储值页「记录」必须跳转到本页
const storedValueJs = fs.readFileSync(path.join(root, 'pages/stored-value/stored-value.js'), 'utf8');
assert.ok(
  storedValueJs.includes('/pages/stored-value-records/stored-value-records'),
  '会员储值页「记录」入口必须跳转储值记录页'
);

console.log('储值记录页路由、空态、token 与分页测试通过');
