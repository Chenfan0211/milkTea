const loginGuard = require('../../utils/login-guard');
const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getUserProfile, refreshUserProfileFromRemote } = require('../../utils/user-profile');
const {
  MAX_STORED_VALUE_QUANTITY,
  buildStoredValueSummary,
  changeStoredValueQuantity,
  normalizePackage,
  pickDefaultPackageIndex
} = require('../../utils/stored-value');
const { resolveStoreCatalog } = require('../../utils/store');

/** 支付结果轮询：微信回调是异步的，需主动查单确认 */
const PAY_POLL_INTERVAL_MS = 1200;
const PAY_POLL_MAX_ATTEMPTS = 8;

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
      // 后台配置的多张储值卡
      packages: [],
      // 当前选中的储值卡（由 packages[selectedIndex] 派生）
      selectedPackage: {},
      selectedIndex: -1,
      quantity: 1,
      maxQuantity: MAX_STORED_VALUE_QUANTITY,
      totalText: '0',
      giftItems: [],
      usageParagraphs: [],
      // 充值进行中：防止重复点击
      recharging: false
    },
    onLoad() {
      this.syncBalance();
      this.syncStore();
      this.loadPackages();
    },
    onShow() {
      this.syncBalance();
      this.syncStore();
    },
    /** 拉取后台配置的储值套餐并默认选中一张 */
    loadPackages() {
      api
        .fetchStoredValuePackages()
        .then(list => {
          if (!Array.isArray(list) || !list.length) {
            // 后台未配置或全部下架：清空选择并给出可读提示，不静默留空
            this.setData({ packages: [], selectedPackage: {}, selectedIndex: -1 });
            this.updateSummary(1);
            return;
          }
          const packages = list.map(normalizePackage);
          const selectedIndex = pickDefaultPackageIndex(packages);
          this.applySelection(packages, selectedIndex, this.data.quantity);
        })
        .catch(() => {
          this.setData({ packages: [], selectedPackage: {}, selectedIndex: -1 });
          this.updateSummary(1);
          wx.showToast({ title: '储值卡加载失败，请稍后重试', icon: 'none' });
        });
    },
    /** 切换选中的储值卡 */
    selectPackage(event) {
      const index = Number(event.currentTarget.dataset.index);
      if (!Number.isInteger(index) || index < 0 || index >= this.data.packages.length) return;
      if (index === this.data.selectedIndex) return;
      this.applySelection(this.data.packages, index, this.data.quantity);
    },
    /** 应用选中的套餐并重算下方赠券与金额 */
    applySelection(packages, selectedIndex, quantity) {
      const selectedPackage = packages[selectedIndex] || {};
      this.setData({ packages, selectedIndex, selectedPackage });
      this.updateSummary(quantity);
    },
    syncBalance() {
      this.setData({ balanceText: String(getUserProfile().balance || 0) });
    },
    syncStore() {
      this.setData({ currentStore: getDisplayStore() });
    },
    updateSummary(quantity) {
      const summary = buildStoredValueSummary(this.data.selectedPackage, quantity);
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
      if (this.data.recharging) return;
      if (this.data.selectedIndex < 0) {
        wx.showToast({ title: '请先选择储值卡', icon: 'none' });
        return;
      }
      loginGuard.requirePhone(() => this.doHandleRecharge(), { reason: '充值需要绑定手机号' });
    },

    /** 充值主流程：建单 -> 发起支付 -> 以服务端状态为准确认结果 */
    doHandleRecharge() {
      const selectedPackage = this.data.selectedPackage || {};
      if (!selectedPackage.id) {
        wx.showToast({ title: '请先选择储值卡', icon: 'none' });
        return;
      }
      const quantity = this.data.quantity;
      this.setData({ recharging: true });
      wx.showLoading({ title: '正在下单', mask: true });

      // 份数通过多次下单实现：每次一张卡，避免在后端引入「份数」概念导致对账复杂
      const orderNos = [];
      const createNext = index => {
        if (index >= quantity) return Promise.resolve(orderNos);
        return api
          .createStoredValueOrder(selectedPackage.id)
          .then(order => {
            orderNos.push(order.orderNo);
            return createNext(index + 1);
          });
      };

      createNext(0)
        .then(list => this.paySequentially(list, 0))
        .catch(error => {
          wx.hideLoading();
          this.setData({ recharging: false });
          wx.showToast({
            title: (error && error.message) || '充值失败，请稍后重试',
            icon: 'none'
          });
        });
    },

    /** 依次支付每笔订单；任一笔失败即中止并提示 */
    paySequentially(orderNos, index) {
      if (index >= orderNos.length) {
        wx.hideLoading();
        this.setData({ recharging: false });
        return Promise.resolve();
      }
      const orderNo = orderNos[index];
      return this.payOne(orderNo).then(paid => {
        if (!paid) {
          wx.hideLoading();
          this.setData({ recharging: false });
          wx.showToast({ title: '支付未完成，可稍后在订单中继续', icon: 'none' });
          return;
        }
        return this.paySequentially(orderNos, index + 1);
      });
    },

    /**
     * 发起单笔支付。
     *
     * 关键：wx.requestPayment 的 success 回调**不代表资金到账**，
     * 必须以服务端订单状态（由微信回调驱动）为准，故支付后要主动查单。
     */
    payOne(orderNo) {
      return api
        .prepayStoredValue(orderNo)
        .then(result => {
          if (!result || !result.params) {
            // mock 通道下后端不返回真实支付参数：
            // 走 mock 支付确认（仅开发/演示环境会出现）
            return this.confirmOrderPaid(orderNo);
          }
          return new Promise(resolve => {
            wx.requestPayment({
              timeStamp: result.params.timeStamp,
              nonceStr: result.params.nonceStr,
              package: result.params.package,
              signType: result.params.signType,
              paySign: result.params.paySign,
              success: () => resolve(this.pollOrder(orderNo)),
              fail: () => resolve(false)
            });
          });
        })
        .catch(() => false);
    },

    /** 轮询查单：微信回调可能晚于 requestPayment 成功回调到达 */
    pollOrder(orderNo) {
      let attempts = 0;
      const tick = () =>
        api
          .fetchStoredValueOrder(orderNo)
          .then(order => {
            if (order && order.payStatus === 'PAID') {
              // 入账成功：刷新余额并提示
              this.syncBalance();
              wx.hideLoading();
              this.setData({ recharging: false });
              wx.showToast({ title: '充值成功', icon: 'success' });
              return true;
            }
            attempts += 1;
            if (attempts >= PAY_POLL_MAX_ATTEMPTS) return false;
            return new Promise(resolve => {
              setTimeout(() => resolve(tick()), PAY_POLL_INTERVAL_MS);
            });
          })
          .catch(() => false);
      return tick();
    },

    /** mock 通道确认（无真实支付参数时）；生产走真实通道不会进入此分支 */
    confirmOrderPaid(orderNo) {
      return api
        .fetchStoredValueOrder(orderNo)
        .then(order => {
          const paid = Boolean(order && order.payStatus === 'PAID');
          if (paid) {
            this.syncBalance();
            wx.hideLoading();
            this.setData({ recharging: false });
            wx.showToast({ title: '充值成功', icon: 'success' });
          }
          return paid;
        })
        .catch(() => false);
    },

    showUnavailable(label) {
      wx.showToast({ title: `${label}暂未接入`, icon: 'none' });
    }
  })
);
