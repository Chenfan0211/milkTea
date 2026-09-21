const loginGuard = require('../../utils/login-guard');
const api = require('../../utils/api');
const { withShare } = require('../../utils/share');
const { giftCardDenominations, giftCardGroups } = require('../../data/mock');

const MAX_QUANTITY = 10;
const AGREEMENT_TITLE = '五零时光单用途商业预付卡章程（协议）';

function findGiftCard(id) {
  return giftCardGroups.reduce((cards, group) => cards.concat(group.cards), []).find(card => card.id === id);
}

function createDenominations() {
  return giftCardDenominations.map((item, index) => ({
    id: item.id,
    faceValue: item.faceValue,
    salePrice: item.salePrice,
    quantity: index === 0 ? 1 : 0
  }));
}

function summarize(denominations) {
  return denominations.reduce(
    (summary, item) => {
      summary.count += item.quantity;
      summary.amount += item.quantity * item.salePrice;
      return summary;
    },
    { count: 0, amount: 0 }
  );
}

function formatMoney(value) {
  return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

function createGiftCardPaymentOrder(giftCard, selected) {
  const summary = summarize(selected);
  return {
    orderType: 'gift-card',
    cardId: giftCard.id,
    cardName: giftCard.name,
    items: selected.map(item => ({
      giftCardId: giftCard.id,
      faceValue: item.faceValue,
      salePrice: item.salePrice,
      quantity: item.quantity
    })),
    totalAmount: summary.amount,
    totalCount: summary.count
  };
}

Page(
  withShare({
    data: {
      giftCard: {},
      denominations: [],
      maxQuantity: MAX_QUANTITY,
      isAgreed: false,
      agreementTitle: AGREEMENT_TITLE,
      totalText: '0',
      totalCount: 0
    },
    onLoad(options) {
      // 礼品卡面额由后台配置
      api
        .fetchGiftCardDenominations()
        .then(list => {
          if (Array.isArray(list) && list.length) this.setData({ denominations: list });
        })
        .catch(() => null);
      const giftCard = findGiftCard(options.id);
      if (!giftCard) {
        wx.showToast({ title: '礼品卡不存在', icon: 'none' });
        setTimeout(() => wx.navigateBack(), 800);
        return;
      }

      const denominations = createDenominations();
      this.setData({ giftCard, denominations }, () => this.updateSummary());
    },
    updateSummary() {
      const summary = summarize(this.data.denominations);
      this.setData({
        totalText: formatMoney(summary.amount),
        totalCount: summary.count
      });
    },
    changeQuantity(event) {
      const { id, delta } = event.currentTarget.dataset;
      const index = this.data.denominations.findIndex(item => item.id === id);
      if (index === -1) return;

      const current = this.data.denominations[index].quantity;
      const next = Math.max(0, Math.min(MAX_QUANTITY, current + Number(delta)));
      if (next === current) return;

      this.setData({ [`denominations[${index}].quantity`]: next }, () => this.updateSummary());
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
      const selected = this.data.denominations.filter(item => item.quantity > 0);
      if (!selected.length) {
        wx.showToast({ title: '请至少选择一份礼品卡', icon: 'none' });
        return;
      }
      if (!this.data.isAgreed) {
        wx.showToast({ title: '请先阅读并同意预付卡章程', icon: 'none' });
        return;
      }

      const paymentOrder = createGiftCardPaymentOrder(this.data.giftCard, selected);
      this.startGiftCardPayment(paymentOrder);
    },
    startGiftCardPayment() {
      wx.showToast({ title: '支付暂未接入', icon: 'none' });
    }
  })
);



