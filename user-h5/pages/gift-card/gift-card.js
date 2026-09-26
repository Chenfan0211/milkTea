const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store');
const {
  pickGiftCardRecords,
  resolveGiftCardDisplay,
  resolveGiftCardImageUrl
} = require('../../utils/gift-card');

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
                groupId,
                name: item.cardName || item.name,
                image: resolveGiftCardImageUrl(item.cardImage || item.image || '')
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
    /** 我的礼品卡：接口直接返回历史展示元数据，不再依赖仅上架面额接口。ACTIVE 即待核销 */
    loadMyCards() {
      api
        .fetchMyGiftCards()
        .then(cards => {
          const cardList = pickGiftCardRecords(cards);
          const decorated = cardList.map(card => {
            const display = resolveGiftCardDisplay(card);
            return Object.assign({}, card, {
              name: display.name,
              image: display.image,
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
      const { id, groupId, cardName } = event.currentTarget.dataset;
      if (!id && !cardName) return;
      const query = [];
      if (id) query.push(`id=${encodeURIComponent(id)}`);
      if (groupId) query.push(`groupId=${encodeURIComponent(groupId)}`);
      if (cardName) query.push(`cardName=${encodeURIComponent(cardName)}`);
      wx.navigateTo({
        url: `/pages/gift-card-purchase/gift-card-purchase?${query.join('&')}`
      });
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

