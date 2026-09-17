const { pointsRecords, userProfile } = require('./data/mock')
const { handleAppHide, resolveLocationContext, resolveStoreCatalog, resolveStorePreference } = require('./utils/store')

function syncStoreGlobals(app, catalog) {
  app.globalData.selectedStoreId = catalog.currentStore ? catalog.currentStore.id : null
  app.globalData.selectedCityCode = catalog.city.code
  app.globalData.selectedCityName = catalog.city.name
}

App({
  globalData: {
    selectedStoreId: null,
    selectedCityCode: 'changsha',
    selectedCityName: '长沙市',
    orderMode: 'pickup',
    menuTabId: 'classic',
    points: userProfile.points,
    signedDates: [],
    continuousDays: 0,
    pointsRecords: pointsRecords.map(item => Object.assign({}, item))
  },
  onLaunch() {
    resolveStorePreference()
    resolveLocationContext()
    syncStoreGlobals(this, resolveStoreCatalog())
  },
  onShow() {
    syncStoreGlobals(this, resolveStoreCatalog())
  },
  onHide() {
    handleAppHide()
  }
})
