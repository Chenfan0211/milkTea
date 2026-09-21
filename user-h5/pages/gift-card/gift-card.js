const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { giftCardGroups } = require('../../data/mock');
const { resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store');

function filterGiftCardGroups(keyword) {
  const normalizedKeyword = String(keyword || '')
    .trim()
    .toLowerCase();
  if (!normalizedKeyword) return giftCardGroups;

  return giftCardGroups
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
      filteredGiftCardGroups: giftCardGroups,
      searchKeyword: '',
      bannerImage: giftCardGroups[0].cards[0].image,
      currentStore: {}
    },
    onLoad() {
      // 礼品卡面额由后台配置
      api
        .fetchGiftCardDenominations()
        .then(list => {
          if (Array.isArray(list) && list.length) this.setData({ denominations: list });
        })
        .catch(() => null);      const catalog = resolveStoreCatalog();
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
        filteredGiftCardGroups: filterGiftCardGroups(searchKeyword)
      });
    },
    clearSearch() {
      this.setData({
        searchKeyword: '',
        filteredGiftCardGroups: giftCardGroups
      });
    }
  })
);

