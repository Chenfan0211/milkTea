import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

for (const page of ['role-center', 'role-workbench', 'role-apply']) {
  for (const extension of ['js', 'json', 'wxml', 'wxss']) {
    assert.ok(fs.existsSync(path.join(root, `packageRole/${page}/${page}.${extension}`)), `missing ${page}.${extension}`);
  }
}

const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(appJson.subpackages?.[0]?.pages?.includes('role-center/role-center'), 'role-center must be registered');
assert.ok(appJson.subpackages?.[0]?.pages?.includes('role-workbench/role-workbench'), 'role-workbench must be registered');
assert.ok(appJson.subpackages?.[0]?.pages?.includes('role-apply/role-apply'), 'role-apply must be registered');

const storage = {};
const toasts = [];
let navigateBackCount = 0;
let relaunchUrl = '';
const navigatedTo = [];

globalThis.getCurrentPages = () => [
  { route: 'packageRole/role-center/role-center' },
  { route: 'packageRole/role-workbench/role-workbench' }
];
globalThis.wx = {
  showShareMenu() {},
  navigateTo(options) {
    navigatedTo.push(options && options.url);
  },
  switchTab() {},
  navigateBack() {
    navigateBackCount += 1;
  },
  reLaunch(options) {
    relaunchUrl = (options && options.url) || '';
  },
  showToast(options) {
    toasts.push(options && options.title);
  },
  getStorageSync(key) {
    return storage[key] || '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  }
};

const {
  ROLE_STATUS_ACTIVE,
  ROLE_STATUS_NONE,
  ROLE_STATUS_PENDING,
  activateRole,
  applyRole,
  getActiveRoles,
  getCurrentBusinessRole,
  getDashboard,
  getIncomeData,
  getPendingRoles,
  getRoleDefinitions,
  getRoleState,
  getRoleStatus,
  getVerifyData,
  hasRole,
  isRoleActive,
  resetRole,
  switchRole,
  switchToConsumer
} = require(path.join(root, 'utils/roles.js'));

// 角色定义
assert.equal(getRoleDefinitions().length, 3, 'must define exactly three business roles');
assert.deepEqual(
  getRoleDefinitions().map(item => item.id),
  ['store', 'investor', 'resource'],
  'role ids must be store/investor/resource'
);
assert.equal(getRoleStatus(), ROLE_STATUS_NONE, 'default state must be none (consumer only)');
assert.equal(getCurrentBusinessRole(), null, 'no business role by default');
assert.equal(isRoleActive(), false, 'role must not be active by default');
assert.equal(getActiveRoles().length, 0, 'no active roles by default');
assert.equal(getPendingRoles().length, 0, 'no pending roles by default');

// 多角色申请：可申请多个不同角色，同一角色不可重复
const appliedStore = applyRole('store');
assert.equal(appliedStore.status, ROLE_STATUS_PENDING, 'applyRole must create pending');
assert.equal(hasRole('store'), true, 'hasRole must reflect pending role');
assert.equal(getPendingRoles().length, 1, 'must have one pending role');
assert.equal(getActiveRoles().length, 0, 'pending role must not be active');
assert.equal(getCurrentBusinessRole(), null, 'pending role must not be current');

const appliedChannel = applyRole('resource');
assert.equal(appliedChannel.status, ROLE_STATUS_PENDING, 'a second different role may be applied');
assert.equal(getPendingRoles().length, 2, 'must have two pending roles');

const duplicate = applyRole('store');
assert.equal(duplicate.status, ROLE_STATUS_PENDING, 'duplicate apply must not change state');
assert.equal(getPendingRoles().length, 2, 'duplicate apply must not add a role');

// 审核通过：仅指定角色转 active，并成为当前角色
activateRole('store');
assert.equal(hasRole('store'), true, 'store must remain owned');
assert.equal(getPendingRoles().length, 1, 'only store should leave pending');
assert.equal(getActiveRoles().length, 1, 'store must be active');
assert.equal(getCurrentBusinessRole().id, 'store', 'active role must become current');

// 切换角色：只能切换到 active 角色
assert.equal(switchRole('resource'), null, 'cannot switch to a pending role');
assert.equal(switchRole('unknown'), null, 'cannot switch to unknown role');

activateRole('resource');
assert.equal(getActiveRoles().length, 2, 'two active roles');
assert.equal(switchRole('resource').id, 'resource', 'can switch between active roles');
assert.equal(getCurrentBusinessRole().id, 'resource', 'current role must update after switch');

// 重置指定角色
resetRole('store');
assert.equal(hasRole('store'), false, 'resetRole must remove the specified role');
assert.equal(getCurrentBusinessRole().id, 'resource', 'current role must be preserved when other role is reset');

// 假数据清理后：不得再有「绕过审核直接开通角色」的演示后门。
// 角色开通只能来自后端 /roles/mine 或审核通过，前端只做视角切换。
resetRole();
assert.equal(
  typeof mockSwitchRole,
  'undefined',
  'mockSwitchRole must be removed (it bypassed role review)'
);
assert.equal(
  switchRole('investor'),
  null,
  'cannot switch to a role that was never activated'
);
assert.equal(getActiveRoles().length, 0, 'no role may be activated without review');

// 正常路径：先提交申请（pending），审核通过后才可切换
applyRole('investor');
assert.equal(switchRole('investor'), null, 'pending role must not be switchable');
activateRole('investor');
assert.equal(switchRole('investor').id, 'investor', 'can switch to an activated role');
assert.equal(getCurrentBusinessRole().id, 'investor', 'current role must update after switch');

applyRole('resource');
activateRole('resource');
assert.equal(switchRole('resource').id, 'resource', 'can switch to another activated role');
assert.equal(getActiveRoles().length, 2, 'switching preserves previously active roles');

// 切回消费端：清空 currentRoleId，但保留已开通角色
switchToConsumer();
assert.equal(getCurrentBusinessRole(), null, 'consumer switch must clear current role');
assert.equal(getActiveRoles().length, 2, 'consumer switch must preserve active roles');

// 重置环境
resetRole();

// 三种角色工作台：结构来自本地 UI 元信息，指标来自后端（无缓存时为 0 而非假数字）
for (const id of ['store', 'investor', 'resource']) {
  const dashboard = getDashboard(id);
  assert.ok(dashboard && dashboard.title, `${id} dashboard must exist`);
  assert.ok(dashboard.metrics.length >= 3, `${id} must expose at least three metrics`);
  assert.ok(
    dashboard.actions.some(item => item.id === 'withdraw'),
    `${id} must include withdraw action`
  );
  assert.ok(dashboard.withdrawable, `${id} must expose withdrawable balance`);
  assert.ok(dashboard.withdrawRule && dashboard.withdrawRule.instantLimit, `${id} must expose withdraw rules`);
  assert.ok(dashboard.settlementNotes.length >= 3, `${id} must expose settlement notes`);
  // 无后端缓存时不得出现历史 mock 金额（原门店「¥328.60 / 42 单」等）
  assert.equal(dashboard._remote, false, `${id} must not claim remote data before fetch`);
  const values = dashboard.metrics.map(m => m.value).join(',');
  assert.ok(!values.includes('328.60'), `${id} must not render mock income figures`);
  assert.ok(!values.includes('1,206.00'), `${id} must not render mock balance figures`);
}

// 收益 / 提现 / 核销在未同步前不得返回假数据
assert.equal(getIncomeData('store'), null, 'income must be null before remote sync');
assert.deepEqual(
  getVerifyData('store'),
  { pool: [], records: [] },
  'verify data must be empty before remote sync'
);

// 分享配置：角色页必须私密且具备标题
const { getShareTitle, isPrivatePage, PAGE_SHARE_TITLES } = require(path.join(root, 'utils/share.js'));
for (const route of [
  'packageRole/role-center/role-center',
  'packageRole/role-workbench/role-workbench',
  'packageRole/role-apply/role-apply'
]) {
  assert.ok(isPrivatePage(route), `${route} must be private for sharing`);
  assert.ok(PAGE_SHARE_TITLES[route], `${route} must define a share title`);
  assert.notEqual(getShareTitle(route), '五零时光新中式养生茶饮', `${route} must not fall back to the default title`);
}

// 我的页：审核中提示、加盟合作跳转、角色功能追加
const profileWxml = fs.readFileSync(path.join(root, 'pages/profile/profile.wxml'), 'utf8');
const profileJs = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');
assert.ok(profileWxml.includes('pending-notice'), 'profile must render pending notice');
assert.ok(profileWxml.includes('pendingRoleCount'), 'profile must show pending role count');
assert.ok(profileWxml.includes('user-card__badges'), 'profile user card must group membership and role badges');
assert.ok(profileWxml.includes('wx:if="{{businessRole}}"'), 'profile must hide the role badge when no current role exists');
assert.ok(profileWxml.includes('user-card__role-badge'), 'profile user card must render the business role badge');
assert.ok(profileWxml.includes('{{businessRole.label}}'), 'profile role badge must show the current role name');
assert.ok(profileWxml.includes('roleFunctions'), 'profile must append role functions');
assert.ok(profileWxml.includes('bindtap="openRoleFunction"'), 'role functions must be clickable');
assert.ok(profileWxml.includes('data-action="{{item.actionId}}"'), 'role functions must carry action id');
assert.ok(profileJs.includes('actionId: item.id'), 'buildRoleFunctions must expose actionId');
assert.ok(profileJs.includes('openRoleFunction'), 'profile must expose openRoleFunction handler');
assert.ok(
  profileJs.includes('/packageRole/role-verify/role-verify'),
  'role function must navigate to dedicated function page'
);
assert.ok(profileJs.includes('/packageRole/role-apply/role-apply'), 'cooperation must navigate to role apply');
assert.ok(profileJs.includes('openRoleApply'), 'profile must expose openRoleApply handler');

// 角色中心：仅切换，无申请/演示工具
const workbenchJs2 = fs.readFileSync(path.join(root, 'packageRole/role-workbench/role-workbench.js'), 'utf8');
const workbenchWxml2 = fs.readFileSync(path.join(root, 'packageRole/role-workbench/role-workbench.wxml'), 'utf8');
assert.ok(workbenchJs2.includes('options.action'), 'workbench must read action option');
assert.ok(workbenchJs2.includes('highlightActionId'), 'workbench must track highlightActionId');
assert.ok(workbenchJs2.includes('focusAction'), 'workbench must implement focusAction');
assert.ok(workbenchWxml2.includes('id="action-{{item.id}}"'), 'workbench action rows must have stable ids');
assert.ok(workbenchWxml2.includes('is-highlight'), 'workbench action rows must support highlight');

const roleCenterJs = fs.readFileSync(path.join(root, 'packageRole/role-center/role-center.js'), 'utf8');
const roleCenterWxml = fs.readFileSync(path.join(root, 'packageRole/role-center/role-center.wxml'), 'utf8');
assert.ok(roleCenterJs.includes('switchRole'), 'role center must switch roles');
assert.ok(
  !roleCenterJs.includes('mockSwitchRole'),
  'role center must not bypass role review via mockSwitchRole'
);
assert.ok(roleCenterJs.includes('switchToConsumer'), 'role center must support consumer switch');
assert.ok(roleCenterWxml.includes('模拟角色切换'), 'role center title must be 模拟角色切换');
assert.ok(roleCenterWxml.includes('消费端'), 'role center must include consumer option');
assert.ok(!roleCenterJs.includes('applyRole'), 'role center must not apply roles');
assert.ok(!roleCenterJs.includes('activateDemoRole'), 'role center must not expose demo activate');
assert.ok(!roleCenterJs.includes('resetDemoRole'), 'role center must not expose demo reset');
assert.ok(!roleCenterWxml.includes('申请开通'), 'role center must not render apply section');
assert.ok(!roleCenterWxml.includes('演示工具'), 'role center must not render demo tools');
assert.ok(!roleCenterWxml.includes('empty-state'), 'role center must always show all roles, no empty state');

// 申请页：按角色动态字段
const roleApplyJs = fs.readFileSync(path.join(root, 'packageRole/role-apply/role-apply.js'), 'utf8');
assert.ok(roleApplyJs.includes('storeName'), 'store role must include storeName field');
assert.ok(roleApplyJs.includes('investBudget'), 'investor role must include investBudget field');
assert.ok(roleApplyJs.includes('resourceLocation'), 'resource role must include resourceLocation field');
assert.ok(roleApplyJs.includes('applyRole'), 'role apply must call applyRole');

console.log('角色多角色模型、切换、申请与角色中心测试通过');
