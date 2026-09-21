const loginGuard = require('../../utils/login-guard');
const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { storedValuePackages, userProfile } = require('../../data/mock');
const {
  MAX_STORED_VALUE_QUANTITY,
  buildStoredValueSummary,
  changeStoredValueQuantity
} = require('../../utils/stored-value');
const { resolveStoreCatalog } = require('../../utils/store');

function getDisplayStore() {
  const catalog = resolveStoreCatalog();
  const store = catalog.currentStore || catalog.stores[0] || {};
  const distanceValue = Number(store.distanceValue);
  return Object.assign({}, store, {
    displayDistance: Number.isFinite(distanceValue) ? `距您${distanceValue.toFixed(1)}km` : store.distanceText || ''
  });
}

Page(
  withShare({
    data: {
      balanceText: '0',
      currentStore: {},
      storedValuePackage: storedValuePackages[0] || {},
      quantity: 1,
      maxQuantity: MAX_STORED_VALUE_QUANTITY,
      totalText: '0',
      giftItems: [],
      usageParagraphs: []
    },
    onLoad() {
      // 储值套餐由后台配置
      api
        .fetchStoredValuePackages()
        .then(list => {
          if (Array.isArray(list) && list.length) this.setData({ packages: list });
        })
        .catch(() => null);      this.syncBalance();
      this.syncStore();
      this.updateSummary(this.data.quantity);
    },
    onShow() {
      this.syncBalance();
      this.syncStore();
    },
    syncBalance() {
      this.setData({ balanceText: String(userProfile.balance || 0) });
    },
    syncStore() {
      this.setData({ currentStore: getDisplayStore() });
    },
    updateSummary(quantity) {
      const summary = buildStoredValueSummary(this.data.storedValuePackage, quantity);
      this.setData({
        quantity: summary.quantity,
        totalText: summary.totalText,
        giftItems: summary.giftItems,
        usageParagraphs: summary.usageParagraphs
      });
    },
    changeQuantity(event) {
      const delta = Number(event.currentTarget.dataset.delta || 0);
      const nextQuantity = changeStoredValueQuantity(this.data.quantity, delta);
      if (nextQuantity === this.data.quantity) return;
      this.updateSummary(nextQuantity);
    },
    selectStore() {
      wx.navigateTo({ url: '/pages/coupon-stores/coupon-stores?from=stored-value' });
    },
    handleRecord() {
      this.showUnavailable('余额记录');
    },
    handleManage() {
      this.showUnavailable('余额管理');
    },
    handleRecharge() {
      loginGuard.requirePhone(() => this.doHandleRecharge(), { reason: '充值需要绑定手机号' });
    },

    doHandleRecharge() {
      this.showUnavailable('储值支付');
    },
    showUnavailable(label) {
      wx.showToast({ title: `${label}暂未接入`, icon: 'none' });
    }
  })
);



