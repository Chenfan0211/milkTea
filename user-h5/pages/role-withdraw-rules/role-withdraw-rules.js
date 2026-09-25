const { withShare } = require('../../utils/share');
const {
  getCurrentBusinessRole,
  getWithdrawRule,
  syncRoleConfigFromRemote,
  syncWithdrawRuleFromRemote
} = require('../../utils/roles');

const ROLE_CENTER_URL = '/pages/role-center/role-center';

Page(
  withShare({
    data: {
      ready: false,
      title: '提现规则',
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
      // 即时额度取后端提现规则接口，说明文案取 app_config.withdraw_rule；
      // 两者都拿不到时用内置兜底，保证规则页始终可读
      Promise.all([syncRoleConfigFromRemote(), syncWithdrawRuleFromRemote()]).then(() => this.render());
      this.render();
    },
    render() {
      const rule = getWithdrawRule();
      this.setData({
        ready: true,
        ruleItems: rule.items,
        ruleFootnotes: [
          '即时到账额度：单笔 ' + rule.instantLimit,
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