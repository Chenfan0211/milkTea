const { withShare } = require('../../utils/share');
const { getRoleDefinitions, getRoleState, mockSwitchRole, switchToConsumer } = require('../../utils/roles');

Page(
  withShare({
    data: {
      roles: [],
      currentRoleId: ''
    },
    onShow() {
      this.syncState();
    },
    syncState() {
      this.setData({
        roles: getRoleDefinitions(),
        currentRoleId: getRoleState().currentRoleId
      });
    },
    switchRole(event) {
      const { id } = event.currentTarget.dataset;
      if (id === this.data.currentRoleId) return;
      const role = mockSwitchRole(id);
      if (!role) return;
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
