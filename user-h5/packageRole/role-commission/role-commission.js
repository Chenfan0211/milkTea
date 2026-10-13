const { withShare } = require('../../utils/share');
const { ensureBusinessRole } = require('../../utils/role-page');
const { getCurrentBusinessRole, getCurrentSubjectId } = require('../../utils/roles');
const api = require('../../utils/api');
const { normalizeCommission } = require('../../utils/commission');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';

Page(
  withShare({
    data: {
      ready: false,
      title: '月度分佣',
      activeStoreId: 'all',
      storeTabs: [],
      filteredStores: [],
      view: null
    },
    onLoad() {
      ensureBusinessRole().then(() => this.syncRole());
    },
    onShow() {
      if (this.data.ready) this.loadCommission();
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role || role.id !== 'investor') {
        this.setData({ ready: false });
        wx.showToast({ title: '仅投资人角色可查看月度分佣', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.setData({ ready: true }, () => this.loadCommission());
    },
    loadCommission() {
      const subjectId = getCurrentSubjectId();
      if (!subjectId) {
        this.setData({ view: null });
        return;
      }
      api
        .fetchInvestorCommission(subjectId)
        .then(data => {
          const view = normalizeCommission(data);
          const storeTabs = [{ id: 'all', label: '全部', count: view.stores.length }].concat(
            view.stores.map(store => ({ id: String(store.id), label: store.name, count: 0 }))
          );
          this.setData({ view, storeTabs }, () => this.applyStoreFilter());
        })
        .catch(() => {
          this.setData({ view: null });
          wx.showToast({ title: '分佣数据加载失败，请稍后重试', icon: 'none' });
        });
    },
    applyStoreFilter() {
      const { view, activeStoreId } = this.data;
      if (!view) return;
      const stores = activeStoreId === 'all' ? view.stores : view.stores.filter(s => String(s.id) === activeStoreId);
      this.setData({ filteredStores: stores });
    },
    switchStore(event) {
      this.setData({ activeStoreId: event.currentTarget.dataset.id }, () => this.applyStoreFilter());
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
