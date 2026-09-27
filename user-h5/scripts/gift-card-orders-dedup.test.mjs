import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 礼品卡订单页去重回归。
 *
 * 背景：原页面同时渲染「我的礼品卡」与「购买订单」两个区块，
 * 同一笔购买（一单一卡绑定）被展示两次。这里锁死：
 *   1) 页面不再有独立的「我的礼品卡」区块，只保留订单列表；
 *   2) 卡号通过 orderId 合并进订单卡，而非独立渲染；
 *   3) 合并函数行为正确。
 */

const wxml = fs.readFileSync(path.join(root, 'pages/gift-card-orders/gift-card-orders.wxml'), 'utf8');
const js = fs.readFileSync(path.join(root, 'pages/gift-card-orders/gift-card-orders.js'), 'utf8');

// 1) 不得再有独立的「我的礼品卡」区块
assert.ok(!wxml.includes('gift-held-section'), '礼品卡订单页不得保留「我的礼品卡」区块');
assert.ok(!wxml.includes('我的礼品卡'), '礼品卡订单页不得再出现「我的礼品卡」标题');
assert.ok(!wxml.includes('filteredGiftCards'), 'WXML 不得再引用 filteredGiftCards');
assert.ok(!js.includes('filteredGiftCards'), 'JS 不得再维护 filteredGiftCards');
assert.ok(!js.includes('decorateGiftCard'), 'JS 不得再保留卡装饰函数（仅合并卡号）');

// 2) 卡号通过合并展示，订单卡仍保留 orderNo 操作
assert.ok(js.includes('mergeCardNoIntoOrders'), '必须提供 orderId -> cardNo 合并函数');
assert.ok(wxml.includes('卡号 {{item.cardNo}}'), '订单卡必须展示合并后的卡号');
assert.ok(wxml.includes('wx:if="{{item.cardNo}}"'), '卡号缺失时必须条件渲染，不留空白');

// 3) 仍保留订单能力（取消/继续支付/退款）
assert.ok(js.includes('requestGiftCardPayment(orderNo)'), '待支付订单仍复用真实支付');
assert.ok(js.includes('refundGiftCard(orderNo'), '已支付未核销订单仍支持退款');
assert.ok(js.includes('cancelGiftCardOrder(orderNo)'), '未支付订单取消仍使用 orderNo');

// 4) 合并函数行为：按 orderId 映射，缺失不报错
const mergeFnMatch = js.match(/function mergeCardNoIntoOrders\(orders, cards\)\s*\{([\s\S]*?)\n\}/);
assert.ok(mergeFnMatch, '必须能定位到 mergeCardNoIntoOrders 函数体');
const mergeBody = mergeFnMatch[1];
assert.ok(mergeBody.includes('card.orderId'), '合并必须按 orderId 关联');
assert.ok(mergeBody.includes('order.id'), '合并必须匹配订单主键');

console.log('礼品卡订单页去重测试通过');
