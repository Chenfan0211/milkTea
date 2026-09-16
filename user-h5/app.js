const { pointsRecords, userProfile } = require('./data/mock')

App({
  globalData: {
    selectedStoreId: 'store-001',
    orderMode: 'pickup',
    menuTabId: 'classic',
    points: userProfile.points,
    signedDates: [],
    continuousDays: 0,
    pointsRecords: pointsRecords.map(item => Object.assign({}, item))
  }
})
