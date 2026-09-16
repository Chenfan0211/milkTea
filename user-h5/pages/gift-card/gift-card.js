const { giftCardGroups, stores } = require('../../data/mock')

function filterGiftCardGroups(keyword) {
  const normalizedKeyword = String(keyword || '').trim().toLowerCase()
  if (!normalizedKeyword) return giftCardGroups

  return giftCardGroups
    .map(group => ({
      id: group.id,
      title: group.title,
      cards: group.cards.filter(card => card.name.toLowerCase().indexOf(normalizedKeyword) !== -1)
    }))
    .filter(group => group.cards.length)
}

Page({
  data: {
    activeTab: 'buy',
    filteredGiftCardGroups: giftCardGroups,
    searchKeyword: '',
    bannerImage: giftCardGroups[0].cards[0].image,
    currentStore: stores[0]
  },
  onLoad() {
    const app = getApp()
    const currentStore = stores.find(store => store.id === app.globalData.selectedStoreId) || stores[0]
    this.setData({ currentStore })
  },
  switchTab(event) {
    this.setData({ activeTab: event.currentTarget.dataset.tab })
  },
  selectStore() {
    wx.showActionSheet({
      itemList: stores.map(store => store.name),
      success: ({ tapIndex }) => {
        const currentStore = stores[tapIndex]
        getApp().globalData.selectedStoreId = currentStore.id
        this.setData({ currentStore })
      }
    })
  },
  goBuy() {
    this.setData({ activeTab: 'buy' })
  },
  handleCard(event) {
    const { id } = event.currentTarget.dataset
    if (!id) return
    wx.navigateTo({ url: `/pages/gift-card-purchase/gift-card-purchase?id=${id}` })
  },
  handleSearchInput(event) {
    const searchKeyword = event.detail.value
    this.setData({
      searchKeyword,
      filteredGiftCardGroups: filterGiftCardGroups(searchKeyword)
    })
  },
  clearSearch() {
    this.setData({
      searchKeyword: '',
      filteredGiftCardGroups: giftCardGroups
    })
  }
})
