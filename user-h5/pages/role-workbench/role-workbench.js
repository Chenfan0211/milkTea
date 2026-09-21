const { withShare } = require('../../utils/share');
const { getActiveRoles, getDashboard, getRoleMeta } = require('../../utils/roles');

const ROLE_CENTER_URL = '/pages/role-center/role-center';

Page(
  withShare({
    data: {
      ready: false,
      role: null,
      dashboard: null,
      title: '角色工作台',
      highlightActionId: ''
    },
    onLoad(options) {
      const requested = options && options.role;
      const action = options && options.action;
      const activeRoles = getActiveRoles();
      const role = activeRoles.find(item => item.id === requested) || null;
      const dashboard = role ? getDashboard(role.id) : null;
      if (!role || !dashboard) {
        this.setData({ ready: false, role: null, dashboard: null, title: '角色工作台' });
        wx.showToast({ title: '暂无权限访问该工作台', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const validAction = dashboard.actions.some(item => item.id === action) ? action : '';
      this.setData({ ready: true, role, dashboard, title: dashboard.title, highlightActionId: validAction }, () => {
        if (validAction) this.focusAction(validAction);
      });
    },
    focusAction(actionId) {
      this.setData({ 'dashboard._highlightId': actionId });
      try {
        wx.pageScrollTo({ selector: '#action-' + actionId, duration: 300 });
      } catch (error) {
        // pageScrollTo 不可用时不阻断；滚动由 scroll-view 兜底。
      }
      if (this._highlightTimer) clearTimeout(this._highlightTimer);
      this._highlightTimer = setTimeout(() => {
        this.setData({ 'dashboard._highlightId': '' });
      }, 1600);
    },
    // 分账说明与提现规则改为入口按钮，避免工作台内联铺大段文字。
    openIncomeRules() {
      wx.navigateTo({ url: '/pages/role-income-rules/role-income-rules' });
    },
    openWithdrawRules() {
      wx.navigateTo({ url: '/pages/role-withdraw-rules/role-withdraw-rules' });
    },
    leaveToRoleCenter() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.reLaunch({ url: ROLE_CENTER_URL });
    },
    handleAction(event) {
      const { id } = event.currentTarget.dataset;
      const routeMap = {
        verify: '/pages/role-verify/role-verify',
        products: '/pages/role-products/role-products',
        invest: '/pages/role-invest/role-invest',
        income: '/pages/role-income/role-income',
        withdraw: '/pages/role-withdraw/role-withdraw',
        orders: '/pages/resource-orders/resource-orders'
      };
      const url = routeMap[id];
      if (!url) {
        wx.showToast({ title: '该功能待接入', icon: 'none' });
        return;
      }
      wx.navigateTo({ url });
    }
  })
);
