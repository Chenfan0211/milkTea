const { withShare } = require('../../utils/share');
const {
  getCityByCode,
  getCityList,
  refreshCitiesFromRemote,
  resolveStoreCatalog,
  selectCity
} = require('../../utils/store');

/** 城市名首字 -> 分组键（省份名，或直辖市用「直辖市」） */
function provinceKey(city) {
  if (city.provinceName) return city.provinceName;
  // 直辖市：城市名即省市同名，直接用「直辖市」
  return '直辖市';
}

/** 按省份分组：直辖市归入「直辖市」，其余按省名分组 */
function buildProvinceGroups() {
  return getCityList()
    .slice()
    .reduce((groups, city) => {
      const key = provinceKey(city);
      let group = groups.find(item => item.key === key);
      if (!group) {
        group = { key, letter: key.slice(0, 1), cities: [] };
        groups.push(group);
      }
      group.cities.push(city);
      return groups;
    }, []);
}

Page(
  withShare({
    data: {
      cityGroups: buildProvinceGroups(),
      indexList: buildProvinceGroups().map(group => group.letter),
      currentCityCode: '4301',
      currentLetter: '湖',
      scrollIntoView: ''
    },
    onLoad(options) {
      refreshCitiesFromRemote().then(() => {
        const catalog = resolveStoreCatalog();
        const groups = buildProvinceGroups();
        this.setData({
          cityGroups: groups,
          indexList: groups.map(group => group.letter),
          currentCityCode: catalog.city.code
        });
      });
      const catalog = resolveStoreCatalog();
      const currentCityCode = options.city || catalog.city.code;
      const currentCity = getCityByCode(currentCityCode);
      const groups = buildProvinceGroups();
      const currentGroup = groups.find(group => group.cities.some(c => c.code === currentCityCode));
      this.setData({
        currentCityCode,
        currentLetter: currentGroup ? currentGroup.letter : currentCity ? currentCity.name.slice(0, 1) : '湖'
      });
    },
    scrollToLetter(event) {
      const { letter } = event.currentTarget.dataset;
      const group = buildProvinceGroups().find(item => item.letter === letter);
      this.setData({ scrollIntoView: group ? `city-group-${group.letter}` : '' });
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
