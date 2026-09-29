const { getCurrentBusinessRole, getActiveRoles, syncRolesFromRemote } = require('./roles');

/**
 * 经营角色页面公共守卫。
 *
 * 背景：packageRole 下所有页面都用「getCurrentBusinessRole() 读本地 Storage」
 * 来判断是否有角色。但角色数据来自异步的 /api/v1/app/roles/mine，
 * 冷启动 / 直接进入（分享、B 端跳转、扫码）时本地尚未同步，
 * getCurrentBusinessRole() 恒返回 null，导致页面误判「暂无权限」并跳回角色中心，
 * 且因为 onShow 的刷新前置条件（ready）永远设不上，接口根本不会被调用。
 *
 * 本守卫把「先确保角色已同步」这一前置步骤收敛到一处：
 *   1) 本地已有 active 角色 -> 直接返回（零额外请求）；
 *   2) 否则先 syncRolesFromRemote() 拉取并写回 Storage，再重读；
 *   3) 仍无 -> 返回 null，由调用方自行提示与跳转。
 *
 * 用法（页面 onLoad）：
 *   const { ensureBusinessRole } = require('../../utils/role-page');
 *   onLoad() {
 *     ensureBusinessRole().then(role => {
 *       if (!role) { this.handleNoRole(); return; }
 *       this.syncRole();  // 原有逻辑，此时 getCurrentBusinessRole() 必能读到
 *     });
 *   }
 *
 * 设计取舍：
 *   - 不改变各页面主体渲染逻辑，只在 onLoad 前插一步，改动面最小、回归风险最低；
 *   - 返回 role（getRoleMeta 对象）而非布尔，便于调用方直接使用 role.id / role.label。
 */

/**
 * 确保当前已有可用的经营角色；无则拉取一次后重读。
 *
 * @returns {Promise<object|null>} 角色 meta（{id,label,...}）；确实无角色时 resolve(null)
 */
function ensureBusinessRole() {
  const local = getCurrentBusinessRole();
  if (local) return Promise.resolve(local);

  return syncRolesFromRemote().then(() => getCurrentBusinessRole() || null);
}

/**
 * 确保角色列表已同步（不要求「当前」角色，供多角色页如工作台使用）。
 * 成功后返回 active 角色 meta 列表；无则空数组。
 */
function ensureRolesLoaded() {
  const local = getActiveRoles();
  if (local.length) return Promise.resolve(local);
  return syncRolesFromRemote().then(() => getActiveRoles());
}

module.exports = { ensureBusinessRole, ensureRolesLoaded };
