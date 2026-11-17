const { withShare } = require('../../utils/share');
const { ensureBusinessRole } = require('../../utils/role-page');
const {
  getCurrentBusinessRole,
  getWithdrawRule,
  syncRoleConfigFromRemote,
  syncWithdrawRuleFromRemote
} = require('../../utils/roles');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';

Page(
  withShare({
    data: {
      ready: false,
      title: '提现规则',
      ruleItems: [],
      ruleFootnotes: []
    },
    onLoad() {
      ensureBusinessRole().then(role => {
        if (!role) {
          wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
          this.leaveToRoleCenter();
          return;
        }
        // 费用与失败说明可远端更新；审核规则始终以所有金额人工审核为准。
        Promise.all([syncRoleConfigFromRemote(), syncWithdrawRuleFromRemote()]).then(() => this.render());
        this.render();
      });
    },
    render() {
      const rule = getWithdrawRule();
      this.setData({
        ready: true,
        ruleItems: rule.items,
        ruleFootnotes: [
          rule.auditNote,
          '提现手续费与单笔上限由后台配置，提交前会展示本次到账金额',
          '如对提现结果有疑问，可通过微信【五零时光】小程序-【我的】-【联系客服】咨询客服',
          '本规则最终解释权归五零时光所有'
        ]
      });
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
