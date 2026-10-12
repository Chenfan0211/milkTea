import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const wxml = fs.readFileSync(path.join(root, 'pages/profile/profile.wxml'), 'utf8');
const wxss = fs.readFileSync(path.join(root, 'pages/profile/profile.wxss'), 'utf8');
const js = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');

// 1. 身份徽章常驻（不再用 wx:if 隐藏），无经营角色时显示「用户」，点击弹出身份切换
assert.ok(!wxml.includes('wx:if="{{businessRole}}"'), '徽章不得再用 wx:if 隐藏（切回用户后入口失联）');
assert.ok(wxml.includes("{{businessRole ? businessRole.label : '用户'}}"), '徽章必须常驻并以「用户」兜底显示当前身份');
assert.ok(wxml.includes('catchtap="switchIdentity"'), '徽章必须可点击触发身份切换');
assert.ok(wxml.includes('aria-label="切换身份"'), '徽章必须带语义化 aria-label');

// 2. 不得再出现常驻切换条（已按需求移除）
assert.ok(!wxml.includes('role-switch-bar'), '切换条必须已移除');
assert.ok(!wxss.includes('.role-switch-bar'), '切换条样式必须已移除');

// 3. ActionSheet 首项用「用户」而非「消费者」，已在用户身份时给反馈
assert.ok(js.includes("'用户（当前）'"), '用户身份时首项必须标注当前');
assert.ok(js.includes('当前已是用户身份'), '已在用户身份时点首项必须给反馈');
assert.ok(!js.includes('消费者'), 'profile.js 不得再出现「消费者」文案');

// 4. 徽章样式仍存在
assert.ok(wxss.includes('.user-card__role-badge'), '徽章样式必须保留');

console.log('「我的」页身份徽章常驻 + 用户文案测试通过');
