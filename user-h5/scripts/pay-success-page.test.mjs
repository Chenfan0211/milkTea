import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 支付成功页结构验收：注册、骨架、文案、Lucide 图标、Tab 跳转、设计 token。
 */

const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(appJson.pages.includes('pages/pay-success/pay-success'), 'app.json 必须注册支付成功页');

const wxml = fs.readFileSync(path.join(root, 'pages/pay-success/pay-success.wxml'), 'utf8');
const wxss = fs.readFileSync(path.join(root, 'pages/pay-success/pay-success.wxss'), 'utf8');
const js = fs.readFileSync(path.join(root, 'pages/pay-success/pay-success.js'), 'utf8');
const json = JSON.parse(fs.readFileSync(path.join(root, 'pages/pay-success/pay-success.json'), 'utf8'));

assert.equal(json.navigationStyle, 'custom', '支付成功页必须使用 custom 导航模式');
assert.equal(
  json.usingComponents['navigation-bar'],
  '/components/navigation-bar/navigation-bar',
  '支付成功页必须注册自定义导航栏'
);
assert.ok(
  wxml.includes('<navigation-bar title="支付结果"') && wxml.includes('back="{{false}}"'),
  '支付成功页必须提供标题且无返回按钮'
);
assert.ok(wxml.includes('circle-check-big.svg'), '支付成功页必须使用 Lucide 成功图标');
assert.ok(
  wxml.includes('支付成功') && wxml.includes('查看订单') && wxml.includes('返回首页'),
  '支付成功页必须包含成功文案与两个操作按钮'
);
assert.ok(wxml.includes('copy.svg') && wxml.includes('copyOrderNo'), '支付成功页必须支持复制订单编号');

// Tab 页跳转必须用 switchTab（navigateTo 会静默失败）
assert.ok(js.includes("wx.switchTab({ url: '/pages/orders/orders' })"), '查看订单必须用 switchTab 跳订单 Tab 页');
assert.ok(js.includes("wx.switchTab({ url: '/pages/home/home' })"), '返回首页必须用 switchTab 跳首页 Tab 页');

// 金额以服务端为准
assert.ok(js.includes('fetchOrderDetail'), '支付成功页必须回读服务端订单金额');

// 设计系统 token
assert.ok(
  wxss.includes('var(--brand-green)') && wxss.includes('var(--price-red)') && wxss.includes('var(--radius-lg)'),
  '支付成功页必须遵守设计系统颜色与圆角 token'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}\b/.test(wxss.replace('#ffffff', '').replace('#FFFFFF', '')),
  '支付成功页 WXSS 不得写死颜色字面量（白色除外）'
);
assert.ok(!/\b\d+px\b/.test(wxss), '支付成功页 WXSS 不得使用 px');

console.log('支付成功页验收测试通过');
