const loginGuard = require('../../utils/login-guard');
const api = require('../../utils/api');
const { withShare } = require('../../utils/share');
const { requestGiftCardPayment } = require('../../utils/gift-card-payment');
const {
  resolveGiftCardImageUrl,
  selectGiftCardDenominations
} = require('../../utils/gift-card');

const AGREEMENT_TITLE = '五零时光单用途商业预付卡章程（协议）';

function formatMoney(value) {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return '0.00';
  return Number.isInteger(amount) ? String(amount) : amount.toFixed(2);
}

function fenToYuan(value) {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return 0;
  return Number((amount / 100).toFixed(2));
}

function readOrderNo(order) {
  return String((order && order.orderNo) || '').trim();
}

Page(
  withShare({
    data: {
      giftCard: {},
      denominations: [],
      selectedDenominationId: '',
      isAgreed: false,
      agreementTitle: AGREEMENT_TITLE,
      totalText: '0.00',
      paying: false
    },
    onLoad(options) {
      const query = options || {};
      // 礼品卡卡面与面额均由后台配置（gift_card_denomination 表）。
      api
        .fetchGiftCardDenominations()
        .then(list => (Array.isArray(list) ? list : []))
        .catch(() => [])
        .then(list => {
          const source = selectGiftCardDenominations(list, {
            groupId: query.groupId,
            cardName: query.cardName,
            id: query.id
          });
          if (!source.length) {
            wx.showToast({ title: '礼品卡不存在', icon: 'none' });
            setTimeout(() => wx.navigateBack(), 800);
            return;
          }
          const first = source[0];
          const groupId = first.groupId || query.groupId || '';
          const cardName = first.cardName || first.name || '';
          const giftCard = {
            id: groupId && cardName ? `${groupId}::${cardName}` : query.id || first.code || '',
            groupId,
            name: cardName,
            image: resolveGiftCardImageUrl(first.cardImage || first.image || '')
          };
          const denominations = source.map((item, index) => ({
            id: item.denominationId || item.id,
            denominationId: item.denominationId || item.id,
            code: item.code,
            faceValue: fenToYuan(item.amount),
            salePrice: fenToYuan(item.salePrice === undefined || item.salePrice === null
              ? item.amount
              : item.salePrice),
            selected: index === 0
          }));
          const selected = denominations[0];
          this.setData({
            giftCard,
            denominations,
            selectedDenominationId: selected.denominationId,
            totalText: formatMoney(selected.salePrice)
          });
        });
    },
    selectDenomination(event) {
      const denominationId = event.currentTarget.dataset.id;
      const selected = this.data.denominations.find(
        item => String(item.denominationId) === String(denominationId)
      );
      if (!selected) return;
      const denominations = this.data.denominations.map(item =>
        Object.assign({}, item, {
          selected: String(item.denominationId) === String(denominationId)
        })
      );
      this.setData({
        denominations,
        selectedDenominationId: selected.denominationId,
        totalText: formatMoney(selected.salePrice)
      });
    },
    toggleAgreement() {
      this.setData({ isAgreed: !this.data.isAgreed });
    },
    showAgreement() {
      wx.showToast({ title: '协议暂未接入', icon: 'none' });
    },
    handlePay() {
      loginGuard.requirePhone(() => this.doHandlePay(), { reason: '购买礼品卡需要绑定手机号' });
    },
    doHandlePay() {
      const selected = this.data.denominations.find(item => item.selected);
      if (!selected) {
        wx.showToast({ title: '请选择一个礼品卡面额', icon: 'none' });
        return;
      }
      if (!this.data.isAgreed) {
        wx.showToast({ title: '请先阅读并同意预付卡章程', icon: 'none' });
        return;
      }
      this.startGiftCardPayment(selected.denominationId);
    },
    startGiftCardPayment(denominationId) {
      if (this.data.paying) return Promise.resolve(false);
      this.setData({ paying: true });
      wx.showLoading({ title: '创建订单中', mask: true });

      return api
        .purchaseGiftCard(denominationId)
        .then(order => {
          const orderNo = readOrderNo(order);
          if (!orderNo) throw new Error('订单创建失败');
          wx.showLoading({ title: '正在支付', mask: true });
          return requestGiftCardPayment(orderNo);
        })
        .then(result => {
          wx.hideLoading();
          this.setData({ paying: false });
          if (result && result.paid) {
            wx.showToast({ title: '购买成功', icon: 'success' });
            setTimeout(() => {
              wx.redirectTo({ url: '/pages/gift-card-orders/gift-card-orders?status=pending_verify' });
            }, 800);
            return true;
          }
          if (result && result.closed) {
            wx.showToast({ title: '订单已关闭', icon: 'none' });
          } else {
            wx.showToast({
              title: result && result.canceled
                ? '支付已取消，可在订单中继续支付'
                : '支付未完成，可在订单中继续支付',
              icon: 'none'
            });
          }
          return false;
        })
        .catch(error => {
          wx.hideLoading();
          this.setData({ paying: false });
          wx.showToast({
            title: (error && error.message) || '支付失败，请稍后重试',
            icon: 'none'
          });
          return false;
        });
    }
  })
);
