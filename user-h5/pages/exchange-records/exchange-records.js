const { withShare } = require('../../utils/share')
const { exchangeRecordCategories, exchangeRecords } = require('../../data/mock')

Page(withShare({
  data: {
    exchangeRecordCategories,
    activeCategory: 'all',
    filteredRecords: exchangeRecords
  },
  switchCategory(event) {
    const { id } = event.currentTarget.dataset
    const filteredRecords = id === 'all'
      ? exchangeRecords
      : exchangeRecords.filter(item => item.status === id)
    this.setData({ activeCategory: id, filteredRecords })
  }
}))
