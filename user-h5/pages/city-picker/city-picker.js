const { withShare } = require('../../utils/share');
const store = require('../../utils/store');

function getCities() {
  return store.getCityList();
}
const { getCityByCode, refreshCitiesFromRemote, resolveStoreCatalog, selectCity } = require('../../utils/store');

function buildCityGroups() {
  return getCities()
    .slice()
    .sort((left, right) => left.initial.localeCompare(right.initial))
    .reduce((groups, city) => {
      let group = groups.find(item => item.initial === city.initial);
      if (!group) {
        group = { initial: city.initial, cities: [] };
        groups.push(group);
      }
      group.cities.push(city);
      return groups;
    }, []);
}

Page(
  withShare({
    data: {
      cityGroups: buildCityGroups(),
      alphabet: buildCityGroups().map(group => group.initial),
      currentCityCode: 'changsha',
      currentInitial: 'C',
      scrollIntoView: ''
    },
    onLoad(options) {
      // 先渲染本地城市，再异步拉取远端（含坐标）后刷新
      refreshCitiesFromRemote().then(() => {
        const catalog = resolveStoreCatalog();
        this.setData({
          cityGroups: buildCityGroups(),
          alphabet: buildCityGroups().map(group => group.initial),
          currentCityCode: catalog.city.code
        });
      });
      const catalog = resolveStoreCatalog();
      const currentCityCode = options.city || catalog.city.code;
      const currentCity = getCityByCode(currentCityCode);
      this.setData({
        currentCityCode,
        currentInitial: currentCity ? currentCity.initial : 'C'
      });
    },
    scrollToInitial(event) {
      const { initial } = event.currentTarget.dataset;
      this.setData({ scrollIntoView: `city-group-${initial}` });
    },
    handleSelectCity(event) {
      const { code } = event.currentTarget.dataset;
      const city = getCityByCode(code);
      if (!city) return;
      selectCity(city.code);
      const app = getApp();
      app.globalData.selectedStoreId = null;
      app.globalData.selectedCityCode = city.code;
      app.globalData.selectedCityName = city.name;
      wx.navigateBack();
    }
  })
);



