const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole, getIncomeRule, syncRoleConfigFromRemote } = require('../../utils/roles');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';

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
      // 结算说明来自 app_config.settlement_notes（运营可改）；
      // 拉取失败时用内置兜底文案，不影响规则页可读性
      syncRoleConfigFromRemote().then(() => this.render());
      this.render();
    },
    render() {
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