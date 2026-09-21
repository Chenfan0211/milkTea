const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole, getIncomeData, getIncomeRule } = require('../../utils/roles');

const ROLE_CENTER_URL = '/pages/role-center/role-center';

Page(
  withShare({
    data: {
      ready: false,
      title: '结算说明',
      ruleItems: [],
      ruleFootnotes: []
    },
    onLoad() {
      const role = getCurrentBusinessRole();
      if (!role) {
        wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      if (!getIncomeData(role.id)) {
        wx.showToast({ title: '当前角色暂无收益数据', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const rule = getIncomeRule();
      this.setData({ ready: true, ruleItems: rule.items, ruleFootnotes: rule.footnotes });
    },
    leaveToRoleCenter() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.reLaunch({ url: ROLE_CENTER_URL });
    }
  })
);