import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pageRoot = path.join(root, 'packageRole/role-apply/role-apply');

for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${pageRoot}.${extension}`), `缺少加盟合作申请页文件: role-apply.${extension}`);
}

// ---------- 环境 stub ----------
const storage = {};
const toasts = [];

globalThis.getCurrentPages = () => [{ route: 'packageRole/role-apply/role-apply' }];
globalThis.wx = {
  showShareMenu() {},
  showToast(options) {
    toasts.push(options && options.title);
  },
  navigateTo() {},
  navigateBack() {},
  reLaunch() {},
  getStorageSync(key) {
    return storage[key] === undefined ? '' : storage[key];
  },
  setStorageSync(key, value) {
    storage[key] = value;
  },
  removeStorageSync(key) {
    delete storage[key];
  }
};

const { applyRole, activateRole, resetRole } = require(path.join(root, 'utils/roles.js'));

let definition;
globalThis.Page = page => {
  definition = page;
};
require(`${pageRoot}.js`);
assert.ok(definition, 'role-apply 页面脚本必须可加载');

function createPageInstance() {
  const instance = Object.assign({}, definition);
  instance.data = JSON.parse(JSON.stringify(definition.data));
  instance.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  return instance;
}

async function flushMicrotasks() {
  await new Promise(resolve => setTimeout(resolve, 20));
}

// ---------- 已是经营角色（active）：不允许再次申请，且卡片显示角色信息 ----------
resetRole();
applyRole('store');
activateRole('store');
{
  const page = createPageInstance();
  definition.onLoad.call(page);
  await flushMicrotasks();
  assert.equal(page.data.ownedRoleStatusMap.store, 'active', '已开通角色必须进入 ownedRoleStatusMap');
  definition.selectRole.call(page, { currentTarget: { dataset: { id: 'store' } } });
  assert.ok(toasts.includes('该角色已开通，无需重复申请'), '已开通角色点击必须提示不可重复申请');
  assert.equal(page.data.selectedRoleId, '', '已开通角色不得被选中');
  assert.equal(page.data.fields.length, 0, '已开通角色不得展开申请表单');
}

// ---------- 已申请待审核（pending）：提示等待审核 ----------
resetRole();
applyRole('investor');
{
  toasts.length = 0;
  const page = createPageInstance();
  definition.onLoad.call(page);
  await flushMicrotasks();
  assert.equal(page.data.ownedRoleStatusMap.investor, 'pending', '待审核角色必须进入 ownedRoleStatusMap');
  definition.selectRole.call(page, { currentTarget: { dataset: { id: 'investor' } } });
  assert.ok(toasts.includes('该角色已申请，等待审核'), '待审核角色点击必须提示等待审核');
  assert.equal(page.data.selectedRoleId, '', '待审核角色不得被选中');
}

// ---------- 未拥有角色：正常选中展开表单 ----------
resetRole();
{
  const page = createPageInstance();
  definition.onLoad.call(page);
  await flushMicrotasks();
  definition.selectRole.call(page, { currentTarget: { dataset: { id: 'store' } } });
  assert.equal(page.data.selectedRoleId, 'store', '未拥有角色必须正常选中');
  assert.ok(page.data.fields.length > 0, '未拥有角色选中后必须展开申请表单');
}

// ---------- 进入页面必须从后端同步角色状态 ----------
{
  const js = fs.readFileSync(`${pageRoot}.js`, 'utf8');
  assert.ok(js.includes('syncRolesFromRemote'), 'onLoad 必须调用 syncRolesFromRemote，避免后端角色与本地缓存不一致');
  assert.ok(js.includes('getRoleState'), '页面必须基于 getRoleState 计算已拥有角色状态');
}

// ---------- 卡片上必须展示角色状态徽标 ----------
const wxml = fs.readFileSync(`${pageRoot}.wxml`, 'utf8');
assert.ok(wxml.includes('ownedRoleStatusMap[item.id]'), '卡片必须读取 ownedRoleStatusMap 渲染状态徽标');
assert.ok(wxml.includes('已开通'), 'active 角色卡片必须显示「已开通」徽标');
assert.ok(wxml.includes('审核中'), 'pending 角色卡片必须显示「审核中」徽标');
assert.ok(
  wxml.includes('role-select__badge--active') && wxml.includes('role-select__badge--pending'),
  '徽标必须区分 active / pending 两种视觉样式'
);

const wxss = fs.readFileSync(`${pageRoot}.wxss`, 'utf8');
assert.ok(wxss.includes('.role-select__badge'), 'WXSS 必须定义状态徽标样式');
assert.ok(wxss.includes('.role-select__card.is-owned'), '已拥有角色卡片必须有弱化视觉（is-owned）');
assert.ok(!/\b\d+px\b/.test(wxss), 'WXSS 不得使用 px 单位');

resetRole();
console.log('加盟合作申请页（已拥有角色禁选 + 状态徽标 + 后端同步）测试通过');
