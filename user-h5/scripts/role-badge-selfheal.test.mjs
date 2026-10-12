import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const ROLE_KEY = 'milkTea:business-role';
const SUBJECT_KEY = 'milkTea:business-role-subject';

// ---- 可控的存储与后端响应 ----
let storage = {};
let rolesResponse = null; // { code, data } —— null 表示网络失败
let requestFailed = false;

globalThis.wx = {
  getStorageSync(key) {
    return key in storage ? storage[key] : '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  },
  removeStorageSync(key) {
    delete storage[key];
  },
  showToast() {},
  showModal() {},
  request({ success, fail }) {
    if (requestFailed) {
      fail(new Error('network down'));
      return;
    }
    success({ data: rolesResponse });
  }
};

const roles = require(path.join(root, 'utils/roles.js'));
const profileJs = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');

function reset({ stored, backend, fail }) {
  storage = {};
  // 模拟已登录：syncRolesFromRemote 未登录会跳过 /roles/mine 请求
  storage['milkTea:auth:token'] = 'test-token';
  if (stored !== undefined) storage[ROLE_KEY] = stored;
  rolesResponse = backend === undefined ? { code: 0, data: { roles: [] } } : backend;
  requestFailed = Boolean(fail);
  // 重置内存态：角色模块无内存缓存，但 subject map 走 storage，已随 storage 清空
}

const STORE_ACTIVE = { roleId: 'store', status: 'active', subjectId: 102, subjectName: '松雅湖吾悦广场店' };

// ============ 用例 1：核心缺陷回归 ============
// 后端返回 active 门店角色、本地从没记过 currentRoleId（首次同步 / 存储被清）
// → 修复前 currentRoleId 被写成 ''，getCurrentBusinessRole() 返回 null，徽章永不显示。
reset({ backend: { code: 0, data: { roles: [STORE_ACTIVE] } } });
await roles.syncRolesFromRemote();
const state1 = roles.getRoleState();
assert.equal(state1.roles.length, 1, '后端 active 角色必须写入本地');
assert.equal(state1.roles[0].status, 'active', '角色状态必须为 active');
assert.equal(state1.currentRoleId, 'store', 'currentRoleId 必须自愈回填，否则徽章永远为空');
assert.ok(roles.getCurrentBusinessRole(), 'getCurrentBusinessRole 不得为 null');
assert.equal(roles.getCurrentBusinessRole().label, '门店', '当前角色必须是门店');
assert.equal(roles.getActiveRoles().length, 1, 'getActiveRoles 必须返回该角色');
assert.equal(
  storage[SUBJECT_KEY] && storage[SUBJECT_KEY].store,
  102,
  '主体 id 必须落库，否则工作台接口取不到 subjectId'
);

// ============ 用例 2：不能覆盖用户手选的其它 active 角色 ============
reset({
  stored: {
    roles: [
      { roleId: 'store', status: 'active' },
      { roleId: 'investor', status: 'active' }
    ],
    currentRoleId: 'investor'
  },
  backend: {
    code: 0,
    data: { roles: [STORE_ACTIVE, { roleId: 'investor', status: 'active', subjectId: 201, subjectName: '投资人甲' }] }
  }
});
await roles.syncRolesFromRemote();
assert.equal(roles.getRoleState().currentRoleId, 'investor', '本地有效的手选角色必须保留，不得被重选覆盖');
assert.equal(roles.getCurrentBusinessRole().id, 'investor', '当前角色仍应为投资人');

// ============ 用例 3：本地角色已解绑 → 必须重选，不得停留在失效角色 ============
reset({
  stored: { roles: [{ roleId: 'store', status: 'active' }], currentRoleId: 'store' },
  backend: { code: 0, data: { roles: [{ roleId: 'investor', status: 'active', subjectId: 201 }] } }
});
await roles.syncRolesFromRemote();
assert.equal(roles.getRoleState().currentRoleId, 'investor', '本地失效角色必须被替换为仍 active 的角色');
assert.equal(roles.getCurrentBusinessRole().id, 'investor', '当前角色必须指向仍开通的角色');

// ============ 用例 4：pending 角色不得被自动选中（未开通不可用） ============
reset({ backend: { code: 0, data: { roles: [{ roleId: 'store', status: 'pending', subjectId: 102 }] } } });
await roles.syncRolesFromRemote();
assert.equal(roles.getRoleState().currentRoleId, '', 'pending 角色不得被自愈选中');
assert.equal(roles.getCurrentBusinessRole(), null, '仅 pending 时当前角色必须为 null');
assert.equal(roles.getPendingRoles().length, 1, 'pending 角色仍须保留供提示审核中');

// ============ 用例 5：消费端主动切回后，不得被同步再次自动进入经营角色 ============
reset({
  backend: { code: 0, data: { roles: [STORE_ACTIVE] } },
  stored: { roles: [{ roleId: 'store', status: 'active' }], currentRoleId: '' }
});
await roles.syncRolesFromRemote();
assert.equal(
  roles.getRoleState().currentRoleId,
  'store',
  '未标记「主动切回消费端」时，仍应自愈回填（这是修复目标本身）'
);
// 显式切回消费端：应清空 currentRoleId 并记录意图
roles.switchToConsumer();
assert.equal(roles.getRoleState().currentRoleId, '', 'switchToConsumer 必须清空当前角色');
await roles.syncRolesFromRemote();
assert.equal(roles.getRoleState().currentRoleId, '', '用户主动切回消费端后，同步不得把身份再次抢回经营角色');

// ============ 用例 6：接口失败必须静默保留缓存、且不写坏数据 ============
reset({ stored: { roles: [{ roleId: 'store', status: 'active' }], currentRoleId: 'store' }, fail: true });
const failed = await roles.syncRolesFromRemote();
assert.equal(failed, null, '接口失败必须返回 null（调用方据此判断）');
assert.equal(roles.getRoleState().currentRoleId, 'store', '接口失败必须保留本地缓存，不得清空徽章');
assert.equal(roles.getCurrentBusinessRole().id, 'store', '接口失败后当前角色必须仍可用');

// ============ 用例 7：「我的」页必须对同步失败兜底告警（此前完全静默） ============
assert.ok(
  /syncRolesFromRemote\(\)[\s\S]*?\.catch\(/.test(profileJs),
  'profile onShow 的经营角色同步必须补 .catch 兜底，否则失败无从排查'
);
assert.ok(profileJs.includes('经营角色同步失败'), 'profile 必须输出可检索的失败日志');

console.log('经营角色徽章自愈与消费端切换测试通过');
