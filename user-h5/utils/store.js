const { cities, stores } = require('../data/mock')

const DEFAULT_CITY_CODE = 'changsha'
const STORE_SELECTION_TTL = 30 * 60 * 1000
const STORAGE_KEYS = {
  LOCATION: 'milkTea:location-context',
  STORE_PREFERENCE: 'milkTea:store-preference',
  LAST_HIDDEN_AT: 'milkTea:last-hidden-at',
  FAVORITE_STORE_IDS: 'milkTea:favorite-store-ids'
}

function getDefaultCity() {
  return cities.find(city => city.code === DEFAULT_CITY_CODE) || cities[0]
}

function getCityByCode(cityCode) {
  return cities.find(city => city.code === cityCode) || null
}

function getStoreById(storeId) {
  return stores.find(store => store.id === storeId) || null
}

function getStoresByCity(cityCode) {
  return stores.filter(store => store.cityCode === cityCode)
}

function calculateDistanceKm(origin, destination) {
  if (!origin || !destination) return 0
  const earthRadiusKm = 6371
  const toRadians = value => value * Math.PI / 180
  const latitudeDelta = toRadians(destination.latitude - origin.latitude)
  const longitudeDelta = toRadians(destination.longitude - origin.longitude)
  const originLatitude = toRadians(origin.latitude)
  const destinationLatitude = toRadians(destination.latitude)
  const haversine = Math.sin(latitudeDelta / 2) ** 2
    + Math.cos(originLatitude) * Math.cos(destinationLatitude) * Math.sin(longitudeDelta / 2) ** 2
  return 2 * earthRadiusKm * Math.asin(Math.sqrt(haversine))
}


function getQueueStatus(queueCount) {
  const count = Math.max(0, Number(queueCount) || 0)
  if (count > 10) {
    return {
      level: 'danger',
      text: `前方${count}杯制作中`,
      icon: '/assets/icons/lucide/queue-danger.svg',
      className: 'queue-status--danger'
    }
  }
  if (count > 5) {
    return {
      level: 'warning',
      text: `前方${count}杯制作中`,
      icon: '/assets/icons/lucide/queue-warning.svg',
      className: 'queue-status--warning'
    }
  }
  return {
    level: 'safe',
    text: `前方${count}杯制作中`,
    icon: '/assets/icons/lucide/queue-safe.svg',
    className: 'queue-status--safe'
  }
}

function decorateStore(store, origin) {
  const distanceValue = calculateDistanceKm(origin, store)
  const distance = distanceValue.toFixed(2)
  const distanceText = distanceValue.toFixed(1)
  const queueStatus = getQueueStatus(store.queueCount)
  return Object.assign({}, store, {
    distanceValue,
    distanceKm: `${distance}km`,
    distanceLabel: `直线${distance}km`,
    distanceText: `距您${distanceText}km`,
    queueText: queueStatus.text,
    queueLevel: queueStatus.level,
    queueIcon: queueStatus.icon,
    queueClass: queueStatus.className
  })
}

function sortStoresByDistance(storeList, origin) {
  return storeList
    .map(store => decorateStore(store, origin))
    .sort((left, right) => left.distanceValue - right.distanceValue)
}

function findNearestStore(cityCode, origin) {
  const nearestStores = sortStoresByDistance(getStoresByCity(cityCode), origin)
  return nearestStores[0] || null
}

function readStorage(key) {
  try {
    return typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(key) : ''
  } catch (error) {
    return ''
  }
}

function writeStorage(key, value) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(key, value)
  } catch (error) {
    // Storage can be unavailable in isolated render environments; the in-memory page state still works.
  }
}

function removeStorage(key) {
  try {
    if (typeof wx !== 'undefined' && wx.removeStorageSync) wx.removeStorageSync(key)
  } catch (error) {
    // Ignore storage failures for the static prototype.
  }
}


function getFavoriteStoreIds() {
  const stored = readStorage(STORAGE_KEYS.FAVORITE_STORE_IDS)
  if (!Array.isArray(stored)) return []
  return Array.from(new Set(stored.filter(storeId => typeof storeId === 'string' && getStoreById(storeId))))
}

function isFavoriteStore(storeId) {
  return getFavoriteStoreIds().indexOf(storeId) !== -1
}

function toggleFavoriteStore(storeId) {
  if (!getStoreById(storeId)) {
    return { storeId, favorite: false, favoriteStoreIds: getFavoriteStoreIds() }
  }
  const favoriteStoreIds = getFavoriteStoreIds()
  const index = favoriteStoreIds.indexOf(storeId)
  const favorite = index === -1
  if (favorite) {
    favoriteStoreIds.push(storeId)
  } else {
    favoriteStoreIds.splice(index, 1)
  }
  writeStorage(STORAGE_KEYS.FAVORITE_STORE_IDS, favoriteStoreIds)
  return { storeId, favorite, favoriteStoreIds }
}

function resolveLocationContext() {
  const stored = readStorage(STORAGE_KEYS.LOCATION)
  const city = stored && getCityByCode(stored.cityCode)
  if (stored && city && Number.isFinite(stored.latitude) && Number.isFinite(stored.longitude)) {
    return Object.assign({}, stored, { cityName: city.name })
  }

  const defaultCity = getDefaultCity()
  return {
    cityCode: defaultCity.code,
    cityName: defaultCity.name,
    latitude: defaultCity.latitude,
    longitude: defaultCity.longitude,
    address: defaultCity.name,
    source: 'default'
  }
}

function saveLocationContext(context) {
  writeStorage(STORAGE_KEYS.LOCATION, context)
  return context
}

function resolveStorePreference(now = Date.now()) {
  const stored = readStorage(STORAGE_KEYS.STORE_PREFERENCE)
  const defaultCity = getDefaultCity()
  let preference = {
    cityCode: defaultCity.code,
    activeStoreId: null,
    selectedAt: 0
  }

  if (stored && typeof stored === 'object') {
    const selectedCity = getCityByCode(stored.cityCode) || defaultCity
    preference = {
      cityCode: selectedCity.code,
      activeStoreId: typeof stored.activeStoreId === 'string' ? stored.activeStoreId : null,
      selectedAt: Number.isFinite(stored.selectedAt) ? stored.selectedAt : 0
    }
  }

  const activeStore = preference.activeStoreId ? getStoreById(preference.activeStoreId) : null
  if (!activeStore || activeStore.cityCode !== preference.cityCode) {
    preference.activeStoreId = null
  }

  const lastHiddenAt = Number(readStorage(STORAGE_KEYS.LAST_HIDDEN_AT))
  if (Number.isFinite(lastHiddenAt) && lastHiddenAt > 0) {
    if (preference.activeStoreId && now - lastHiddenAt > STORE_SELECTION_TTL) {
      preference.activeStoreId = null
      preference.selectedAt = 0
    }
    removeStorage(STORAGE_KEYS.LAST_HIDDEN_AT)
  }

  writeStorage(STORAGE_KEYS.STORE_PREFERENCE, preference)
  return preference
}

function handleAppHide(now = Date.now()) {
  writeStorage(STORAGE_KEYS.LAST_HIDDEN_AT, now)
}

function selectCity(cityCode, now = Date.now()) {
  const city = getCityByCode(cityCode)
  if (!city) return resolveStorePreference(now)
  const preference = resolveStorePreference(now)
  preference.cityCode = city.code
  preference.activeStoreId = null
  preference.selectedAt = now
  writeStorage(STORAGE_KEYS.STORE_PREFERENCE, preference)
  return preference
}

function selectStore(storeId, now = Date.now()) {
  const store = getStoreById(storeId)
  if (!store) return resolveStorePreference(now)
  const preference = resolveStorePreference(now)
  preference.cityCode = store.cityCode
  preference.activeStoreId = store.id
  preference.selectedAt = now
  writeStorage(STORAGE_KEYS.STORE_PREFERENCE, preference)
  return preference
}

function useDeviceLocation(now = Date.now()) {
  const location = resolveLocationContext()
  const city = getCityByCode(location.cityCode) || getDefaultCity()
  const preference = {
    cityCode: city.code,
    activeStoreId: null,
    selectedAt: now
  }
  writeStorage(STORAGE_KEYS.STORE_PREFERENCE, preference)
  return { location, city, preference }
}

function getSortOrigin(city, location) {
  if (location && location.cityCode === city.code) {
    return { latitude: location.latitude, longitude: location.longitude }
  }
  return { latitude: city.latitude, longitude: city.longitude }
}

function resolveStoreCatalog(now = Date.now()) {
  let preference = resolveStorePreference(now)
  const city = getCityByCode(preference.cityCode) || getDefaultCity()
  const location = resolveLocationContext()
  const origin = getSortOrigin(city, location)
  const cityStores = sortStoresByDistance(getStoresByCity(city.code), origin)
  const currentStore = preference.activeStoreId
    ? cityStores.find(store => store.id === preference.activeStoreId) || null
    : null

  return {
    preference,
    location,
    city,
    origin,
    stores: cityStores,
    currentStore
  }
}

module.exports = {
  DEFAULT_CITY_CODE,
  STORE_SELECTION_TTL,
  STORAGE_KEYS,
  calculateDistanceKm,
  findNearestStore,
  getCityByCode,
  getStoreById,
  getStoresByCity,
  getFavoriteStoreIds,
  getQueueStatus,
  handleAppHide,
  isFavoriteStore,
  resolveLocationContext,
  resolveStoreCatalog,
  resolveStorePreference,
  saveLocationContext,
  selectCity,
  selectStore,
  sortStoresByDistance,
  toggleFavoriteStore,
  useDeviceLocation
}
