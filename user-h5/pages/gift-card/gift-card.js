const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store');

function filterGiftCardGroups(keyword, groups) {
  const source = Array.isArray(groups) ? groups : [];
  const normalizedKeyword = String(keyword || '')
    .trim()
    .toLowerCase();
  if (!normalizedKeyword) return source;

  return source
    .map(group => ({
      id: group.id,
      title: group.title,
      cards: group.cards.filter(card => card.name.toLowerCase().indexOf(normalizedKeyword) !== -1)
    }))
    .filter(group => group.cards.length);
}

Page(
  withShare({
    data: {
      activeTab: 'buy',
      giftCardGroups: [],
      filteredGiftCardGroups: [],
      searchKeyword: '',
      bannerImage: '',
      currentStore: {},
      myCards: [],
      myCardsLoaded: false
    },
    /** 由后台礼品卡面额（gift_card_denomination）聚合为「分组 -> 卡面」结构 */
    loadGiftCardGroups() {
      api
        .fetchGiftCardDenominations()
        .then(list => (Array.isArray(list) ? list : []))
        .catch(() => [])
        .then(list => {
          const groupMap = {};
          const groups = [];
          list.forEach(item => {
            const groupId = item.groupId || 'default';
            if (!groupMap[groupId]) {
              groupMap[groupId] = {
                id: groupId,
                title: item.groupTitle || '礼品卡',
                cards: []
              };
              groups.push(groupMap[groupId]);
            }
            const cardId = item.cardName || item.code;
            if (!groupMap[groupId].cards.some(card => card.id === cardId)) {
              groupMap[groupId].cards.push({
                id: cardId,
                name: item.cardName || item.name,
                image: item.cardImage || ''
              });
            }
          });
          const firstCard = groups[0] && groups[0].cards[0];
          this.setData({
            giftCardGroups: groups,
            filteredGiftCardGroups: groups,
            bannerImage: firstCard ? firstCard.image : ''
          });
        });
    },
    /** 我的礼品卡：/api/v1/app/gift-cards，关联面额补 name / image；ACTIVE 即待核销 */
    loadMyCards() {
      Promise.all([api.fetchMyGiftCards(), api.fetchGiftCardDenominations()])
        .then(([cards, denominations]) => {
          const cardList = Array.isArray(cards) ? cards : [];
          const denomMap = {};
          (Array.isArray(denominations) ? denominations : []).forEach(d => {
            denomMap[d.id] = d;
          });
          const decorated = cardList.map(card => {
            const denom = denomMap[card.denominationId] || {};
            return Object.assign({}, card, {
              name: denom.cardName || denom.name || '礼品卡',
              image: denom.cardImage || '/assets/images/3x/gift-card-matcha.jpg',
              pendingVerify: card.status === 'ACTIVE'
            });
          });
          this.setData({ myCards: decorated, myCardsLoaded: true });
        })
        .catch(() => this.setData({ myCards: [], myCardsLoaded: true }));
    },
    onLoad(options) {
      // 支持 ?tab=mine 直接打开「我的礼品卡」
      if (options && options.tab === 'mine') this.setData({ activeTab: 'mine' });
      // 礼品卡面额由后台配置
      api
        .fetchGiftCardDenominations()
        .then(list => {
          if (Array.isArray(list) && list.length) this.setData({ denominations: list });
        })
        .catch(() => null);
      this.loadGiftCardGroups();
      this.loadMyCards();
      const catalog = resolveStoreCatalog();
      this.setData({ currentStore: catalog.currentStore || catalog.stores[0] || {} });
    },
    switchTab(event) {
      this.setData({ activeTab: event.currentTarget.dataset.tab });
    },
    selectStore() {
      const catalog = resolveStoreCatalog();
      const availableStores = catalog.stores;
      wx.showActionSheet({
        itemList: availableStores.map(store => store.name),
        success: ({ tapIndex }) => {
          const currentStore = availableStores[tapIndex];
          persistSelectedStore(currentStore.id);
          getApp().globalData.selectedStoreId = currentStore.id;
          this.setData({ currentStore });
        }
      });
    },
    goBuy() {
      this.setData({ activeTab: 'buy' });
    },
    handleCard(event) {
      const { id } = event.currentTarget.dataset;
      if (!id) return;
      wx.navigateTo({ url: `/pages/gift-card-purchase/gift-card-purchase?id=${id}` });
    },
    handleSearchInput(event) {
      const searchKeyword = event.detail.value;
      this.setData({
        searchKeyword,
        filteredGiftCardGroups: filterGiftCardGroups(searchKeyword, this.data.giftCardGroups)
      });
    },
    clearSearch() {
      this.setData({
        searchKeyword: '',
        filteredGiftCardGroups: this.data.giftCardGroups || []
      });
    }
  })
);

