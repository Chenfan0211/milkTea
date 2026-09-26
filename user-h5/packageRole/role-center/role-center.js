const { withShare } = require('../../utils/share');
const {
  getRoleDefinitions,
  getRoleState,
  syncRolesFromRemote,
  switchRole,
  switchToConsumer
} = require('../../utils/roles');

Page(
  withShare({
    data: {
      roles: [],
      currentRoleId: '',
      activeRoleIds: []
    },
    onShow() {
      this.syncState();
      // 角色以后端为准：同步用户的真实角色与绑定主体（失败时保留本地兜底）
      syncRolesFromRemote().then(() => this.syncState());
    },
    syncState() {
      const state = getRoleState();
      this.setData({
        roles: getRoleDefinitions(),
        currentRoleId: state.currentRoleId,
        activeRoleIds: state.roles
          .filter(item => item.status === 'active')
          .map(item => item.roleId)
      });
    },
    // 仅允许切换到后端已开通（active）的角色。
    // 原实现可把任意角色直接置为已开通（绕过审核），
    // 现改为只做视角切换，开通状态一律以后端 /roles/mine 为准。
    switchRole(event) {
      const { id } = event.currentTarget.dataset;
      if (id === this.data.currentRoleId) return;
      const role = switchRole(id);
      if (!role) {
        wx.showToast({ title: '该角色尚未开通，请先提交申请', icon: 'none' });
        return;
      }
      this.syncState();
      wx.showToast({ title: '已切换为' + role.label, icon: 'none' });
    },
    switchConsumer() {
      if (!this.data.currentRoleId) return;
      switchToConsumer();
      this.syncState();
      wx.showToast({ title: '已切回消费端', icon: 'none' });
    }
  })
);
