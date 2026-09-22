const api = require('./api');
const { isPlatformListed } = require('./product-catalog');

const LISTING_STORAGE_KEY = 'milkTea:product-listing';

// 菜单内存镜像。
//
// 阶段 C 后菜单来自 /api/v1/app/menu：页面先 await refreshMenuFromRemote()，
// 之后同步函数（getListedMenuTabs 等）读镜像。未拉取到时返回空数组，
// 页面按「无菜单」处理，不再回退本地假数据。
let menuCatalog = [];

/** 用远端数据刷新菜单镜像（页面 onLoad 时调用） */
function refreshMenuFromRemote() {
  return api
    .fetchMenu()
    .then(list => {
      if (Array.isArray(list) && list.length) {
        menuCatalog = list;
      }
      return menuCatalog;
    })
    .catch(() => menuCatalog);
}

/** 直接注入菜单镜像（仅供测试使用）。 */
function setMenuCatalogForTest(list) {
  menuCatalog = Array.isArray(list) ? list : [];
  return menuCatalog;
}

/** 当前菜单镜像（只读） */
function getMenuCatalog() {
  return menuCatalog;
}

// 上架状态按门店隔离：{ [storeId]: { [productId]: boolean } }
// 未记录的默认视为上架，避免新增商品时漏配。
function normalizeState(value) {
  if (!value || typeof value !== 'object') return {};
  const state = {};
  Object.keys(value).forEach(storeId => {
    const entry = value[storeId];
    if (!entry || typeof entry !== 'object') return;
    const listed = {};
    Object.keys(entry).forEach(productId => {
      const flag = entry[productId];
      if (typeof flag === 'boolean') listed[productId] = flag;
    });
    state[storeId] = listed;
  });
  return state;
}

function readState() {
  try {
    const value = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(LISTING_STORAGE_KEY) : '';
    return normalizeState(value);
  } catch (error) {
    return {};
  }
}

function writeState(state) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) {
      wx.setStorageSync(LISTING_STORAGE_KEY, normalizeState(state));
    }
  } catch (error) {
    // 存储不可用时保持内存结果可用。
  }
}

// 拍平菜单得到全量商品，附带所属 tab / 分组 / 分类。
function flattenProducts(menus) {
  const list = [];
  (menus || []).forEach(tab => {
    (tab.groups || []).forEach(group => {
      (group.categories || []).forEach(category => {
        (category.products || []).forEach(product => {
          list.push({
            id: product.id,
            name: product.name,
            price: product.price,
            originalPrice: product.originalPrice,
            image: product.image,
            badgeIcon: product.badgeIcon,
            tabId: tab.id,
            tabLabel: tab.label,
            groupId: group.id,
            groupLabel: group.label,
            categoryId: category.id,
            categoryLabel: category.label,
            product
          });
        });
      });
    });
  });
  return list;
}

// 门店可选商品 = 运营后台已上架的商品；后台未上架的商品不进入选品范围。
function getAllProducts() {
  return flattenProducts(menuCatalog).filter(item => isPlatformListed(item.id));
}

// 判断某商品在指定门店是否上架；未配置时默认上架。
function isListed(storeId, productId, state) {
  const source = state || readState();
  const entry = source[storeId];
  if (!entry || typeof entry[productId] !== 'boolean') return true;
  return entry[productId];
}

// 按门店筛选商品清单（用于选品页），保留 isListed 标记。
function getProductsForStore(storeId) {
  const state = readState();
  return getAllProducts().map(item =>
    Object.assign({}, item, { listed: isListed(storeId, item.id, state) })
  );
}

// 统计某门店的上架情况，供状态角标使用。
function getListingStats(storeId) {
  const products = getProductsForStore(storeId);
  const listedCount = products.filter(item => item.listed).length;
  return {
    total: products.length,
    listed: listedCount,
    unlisted: products.length - listedCount
  };
}

// 单一商品上下架。
function setListed(storeId, productId, listed) {
  if (!storeId || !productId) return getListingStats(storeId);
  const state = readState();
  const entry = Object.assign({}, state[storeId]);
  entry[productId] = Boolean(listed);
  state[storeId] = entry;
  writeState(state);
  return getListingStats(storeId);
}

// 批量上下架，一次写入避免多次触发存储。
function setListedBatch(storeId, productIds, listed) {
  if (!storeId || !Array.isArray(productIds) || !productIds.length) return getListingStats(storeId);
  const state = readState();
  const entry = Object.assign({}, state[storeId]);
  productIds.forEach(productId => {
    if (productId) entry[productId] = Boolean(listed);
  });
  state[storeId] = entry;
  writeState(state);
  return getListingStats(storeId);
}

// 恢复某门店的默认上架状态（全部上架）。
function resetListing(storeId) {
  const state = readState();
  delete state[storeId];
  writeState(state);
  return getListingStats(storeId);
}

// 单个商品详情：仅在运营后台已上架时可查，附带门店上下架状态与规格。
function getProductDetail(storeId, productId) {
  if (!productId) return null;
  const item = flattenProducts(menuCatalog).find(entry => entry.id === productId);
  if (!item) return null;
  if (!isPlatformListed(productId)) return null;
  const product = item.product || {};
  return {
    id: item.id,
    name: item.name,
    price: item.price,
    originalPrice: item.originalPrice,
    storedValuePrice: item.product.storedValuePrice,
    image: item.image,
    galleryImage: product.galleryImage || item.image,
    badgeIcon: item.badgeIcon,
    tags: (item.product.tags || []).slice(),
    description: product.description || '',
    ingredients: product.ingredients || '',
    allergens: product.allergens || '',
    cupCapacity: product.cupCapacity || '',
    tips: product.tips || [],
    imageDisclaimer: product.imageDisclaimer || '',
    promotionText: product.promotionText || '',
    tabLabel: item.tabLabel,
    groupLabel: item.groupLabel,
    categoryId: item.categoryId,
    categoryLabel: item.categoryLabel,
    platformListed: true,
    listed: isListed(storeId, item.id),
    specGroups: (product.specGroups || []).map(group => ({
      id: group.id,
      label: group.label,
      options: (group.options || []).map(option => ({
        id: option.id,
        label: option.label,
        priceDelta: option.priceDelta || 0
      }))
    }))
  };
}

// 按门店过滤后返回菜单，供消费端（点单页 / 适用商品页）使用。
// 分类被清空时整组隐藏，分组与 Tab 同理，避免消费端出现空分类。
function applyListing(storeId, menus) {
  const source = menus || menuCatalog;
  const state = readState();
  return source
    .map(tab => {
      const groups = (tab.groups || [])
        .map(group => {
          const categories = (group.categories || [])
            .map(category => {
              const products = (category.products || []).filter(
                product => isPlatformListed(product.id) && isListed(storeId, product.id, state)
              );
              if (!products.length) return null;
              return Object.assign({}, category, { products });
            })
            .filter(Boolean);
          if (!categories.length) return null;
          return Object.assign({}, group, { categories });
        })
        .filter(Boolean);
      if (!groups.length) return null;
      return Object.assign({}, tab, { groups });
    })
    .filter(Boolean);
}

// 消费端读取：指定门店的上架菜单；无门店时返回完整菜单。
function getListedMenuTabs(storeId) {
  if (!storeId) return menuCatalog;
  const tabs = applyListing(storeId, menuCatalog);
  return tabs.length ? tabs : menuCatalog;
}

// 商品最终是否可售 = 运营后台已上架 且 门店已上架。
function isProductListed(storeId, productId) {
  if (!isPlatformListed(productId)) return false;
  return isListed(storeId, productId);
}

module.exports = {
  LISTING_STORAGE_KEY,
  isPlatformListed,
  refreshMenuFromRemote,
  setMenuCatalogForTest,
  getMenuCatalog,
  applyListing,
  getAllProducts,
  getListedMenuTabs,
  getListingStats,
  getProductDetail,
  getProductsForStore,
  isProductListed,
  resetListing,
  setListed,
  setListedBatch
};
