const { withShare } = require('../../utils/share');
const {
  getCurrentBusinessRole,
  getResourceOrders,
  syncResourceFromRemote,
  INCOME_STATUS_TEXT
} = require('../../utils/roles');
const { formatDateTime } = require('../../utils/date-format');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';

Page(
  withShare({
    data: {
      ready: false,
      title: '门店提成订单',
      activeStoreId: 'all',
      storeTabs: [],
      groups: [],
      stores: [],
      count: 0,
      totalText: '¥0.00',
      pendingCount: 0,
      detailVisible: false,
      detail: null
    },
    onLoad() {
      this.syncRole();
    },
    onShow() {
      if (!this.data.ready) return;
      // 绑定门店与提成订单来自后端；拉到后重渲染，失败保留现有列表
      syncResourceFromRemote().then(() => this.syncOrders());
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role || role.id !== 'resource') {
        this.setData({ ready: false });
        wx.showToast({ title: '仅资源方角色可查看门店提成', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.setData({ ready: true }, () => {
        // 先渲染缓存（可能为空），随后拉取真实数据
        this.syncOrders();
        syncResourceFromRemote().then(() => this.syncOrders());
      });
    },
    // 提成订单按绑定门店归属，支持按门店筛选。
    syncOrders() {
      const role = getCurrentBusinessRole();
      if (!role) return;
      const data = getResourceOrders(role.id, this.data.activeStoreId);
      if (!data) {
        this.setData({ ready: false });
        return;
      }
      const storeTabs = [{ id: 'all', label: '全部', count: data.count }].concat(
        data.stores.map(store => ({
          id: store.id,
          label: store.name,
          count: getResourceOrders(role.id, store.id).count
        }))
      );
      this.setData({
        stores: data.stores,
        groups: data.groups.map(group =>
          Object.assign({}, group, {
            orders: group.orders.map(order =>
              Object.assign({}, order, {
                statusLabel: INCOME_STATUS_TEXT[order.status] || order.status,
                timeText: formatDateTime(order.time)
              })
            )
          })
        ),
        storeTabs,
        count: data.count,
        totalText: '¥' + data.totalText.replace('+', ''),
        pendingCount: data.pendingCount
      });
    },
    switchStore(event) {
      this.setData({ activeStoreId: event.currentTarget.dataset.id }, () => this.syncOrders());
    },
    openOrder(event) {
      const id = event.currentTarget.dataset.id;
      if (!id) return;
      let target = null;
      for (const group of this.data.groups) {
        const found = group.orders.find(order => order.id === id);
        if (found) {
          target = Object.assign({}, found, {
            storeName: group.name,
            timeText: formatDateTime(found.time),
            timeline: (found.timeline || []).map(step =>
              Object.assign({}, step, { timeText: step.time ? formatDateTime(step.time) : '' })
            )
          });
          break;
        }
      }
      if (!target) return;
      this.setData({ detailVisible: true, detail: target });
    },
    closeOrder() {
      this.setData({ detailVisible: false, detail: null });
    },
    copyOrderNo(event) {
      const orderNo = event.currentTarget.dataset.orderNo;
      if (!orderNo) return;
      wx.setClipboardData({
        data: orderNo,
        success: () => wx.showToast({ title: '订单号已复制', icon: 'none' }),
        fail: () => wx.showToast({ title: '复制失败，请重试', icon: 'none' })
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