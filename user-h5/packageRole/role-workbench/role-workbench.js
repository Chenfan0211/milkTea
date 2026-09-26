const { withShare } = require('../../utils/share');
const { getActiveRoles, getDashboard, warmUpRoleData } = require('../../utils/roles');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';

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
      // 概览 / 核销 / 订单等数据全部来自后端；失败时保留上面的兜底渲染
      warmUpRoleData(role.id).then(() => {
        const next = getDashboard(role.id);
        if (next) this.setData({ dashboard: next, title: next.title });
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
      wx.navigateTo({ url: '/packageRole/role-income-rules/role-income-rules' });
    },
    openWithdrawRules() {
      wx.navigateTo({ url: '/packageRole/role-withdraw-rules/role-withdraw-rules' });
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
        verify: '/packageRole/role-verify/role-verify',
        products: '/packageRole/role-products/role-products',
        invest: '/packageRole/role-invest/role-invest',
        income: '/packageRole/role-income/role-income',
        withdraw: '/packageRole/role-withdraw/role-withdraw',
        orders: '/packageRole/resource-orders/resource-orders'
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
