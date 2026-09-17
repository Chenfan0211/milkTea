const { withShare } = require('../../utils/share')
const { cities } = require('../../data/mock')
const { resolveStoreCatalog, selectCity } = require('../../utils/store')

function buildCityGroups() {
  return cities
    .slice()
    .sort((left, right) => left.initial.localeCompare(right.initial))
    .reduce((groups, city) => {
      let group = groups.find(item => item.initial === city.initial)
      if (!group) {
        group = { initial: city.initial, cities: [] }
        groups.push(group)
      }
      group.cities.push(city)
      return groups
    }, [])
}

Page(withShare({
  data: {
    cityGroups: buildCityGroups(),
    alphabet: buildCityGroups().map(group => group.initial),
    currentCityCode: 'changsha',
    currentInitial: 'C',
    scrollIntoView: ''
  },
  onLoad(options) {
    const catalog = resolveStoreCatalog()
    const currentCityCode = options.city || catalog.city.code
    const currentCity = cities.find(city => city.code === currentCityCode)
    this.setData({
      currentCityCode,
      currentInitial: currentCity ? currentCity.initial : 'C'
    })
  },
  scrollToInitial(event) {
    const { initial } = event.currentTarget.dataset
    this.setData({ scrollIntoView: `city-group-${initial}` })
  },
  handleSelectCity(event) {
    const { code } = event.currentTarget.dataset
    const city = cities.find(item => item.code === code)
    if (!city) return
    selectCity(city.code)
    const app = getApp()
    app.globalData.selectedStoreId = null
    app.globalData.selectedCityCode = city.code
    app.globalData.selectedCityName = city.name
    wx.navigateBack()
  }
}))
