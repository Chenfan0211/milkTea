const api = require('./api');
const { isPlatformListed } = require('./product-catalog');

const LISTING_STORAGE_KEY = 'milkTea:product-listing';

// 菜单内存镜像。
//
// 阶段 C 后菜单来自 /api/v1/app/menu：页面先 await refreshMenuFromRemote()，
// 之后同步函数（getListedMenuTabs 等）读镜像。未拉取到时返回空数组，
// 页面按「无菜单」处理，不再回退本地假数据。
let menuCatalog = [];

// 菜单镜像的最近一次同步状态，供页面区分「首次加载失败」与「已有镜像的刷新失败」。
// 前者必须让用户知道（否则页面会一直空着且无从重试），后者保留旧镜像即可。
let menuSyncState = { loaded: false, error: null, updatedAt: 0 };

/**
 * 用远端数据刷新菜单镜像（页面每次展示时调用）。
 *
 * 约定：
 *   · 请求成功但返回空数组 -> 不清空镜像。宁可用上一份数据，也不要把页面打空
 *     （后端瞬时返回空比展示旧菜单更糟：用户会以为门店没商品）。
 *   · 请求失败 -> 保留旧镜像，但把错误记进 menuSyncState，让页面能区分
 *     「首次就没拿到」和「只是这次刷新失败」，前者需要提示用户重试。
 */
function refreshMenuFromRemote() {
  return api
    .fetchMenu()
    .then(list => {
      if (Array.isArray(list) && list.length) {
        menuCatalog = list;
      }
      menuSyncState = { loaded: true, error: null, updatedAt: Date.now() };
      return menuCatalog;
    })
    .catch(error => {
      menuSyncState = {
        loaded: menuCatalog.length > 0,
        error: error || new Error('菜单加载失败'),
        updatedAt: menuSyncState.updatedAt
      };
      return menuCatalog;
    });
}

/** 最近一次菜单同步状态（只读），页面据此决定是否需要提示「加载失败」。 */
function getMenuSyncState() {
  return menuSyncState;
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
//
// applyListing 结果为空有两种成因，必须区分对待：
//   1) menuCatalog 本身为空 -> 返回空数组，页面按「无菜单」处理；
//   2) 有菜单但该门店可售商品为 0 -> 回落完整菜单，避免整页空白。
function getListedMenuTabs(storeId) {
  if (!menuCatalog.length) return menuCatalog;
  if (!storeId) return menuCatalog;
  const tabs = applyListing(storeId, menuCatalog);
  return tabs.length ? tabs : menuCatalog;
}

/**
 * 合并全部一级页签为单一菜单（点单页不展示顶部页签）。
 *
 * 产品口径：原「招牌主打」等其余 TAB 的分组，与第一个 TAB 的分组同级展示。
 * 安全性：group / category 的 id 均取自数据库全局唯一的 code，合并后不会冲突
 * （已用真实分类树 8 条数据校验：recommend/leaf/featured-signature 与
 *  herbal/traditional/featured-season 均无重复）。
 *
 * 注意：合并只做「分组扁平化」，不改变分组内商品与分类的从属关系，
 * 因此左侧栏仍按「分组标签 + 其下分类」渲染，选中态逻辑无需调整。
 *
 * 契约：恒返回带 groups 数组的对象（无菜单时为 { groups: [] }），
 * 消费端无需再各自判空，避免 menu.groups[0] 触发 undefined 访问。
 */
function getMergedMenuTab(storeId) {
  const tabs = getListedMenuTabs(storeId);
  if (!tabs.length) return { groups: [] };
  const first = tabs[0];
  return Object.assign({}, first, {
    groups: tabs.reduce((acc, tab) => acc.concat(tab.groups || []), [])
  });
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
  getMenuSyncState,
  setMenuCatalogForTest,
  getMenuCatalog,
  applyListing,
  getAllProducts,
  getListedMenuTabs,
  getMergedMenuTab,
  getListingStats,
  getProductDetail,
  getProductsForStore,
  isProductListed,
  resetListing,
  setListed,
  setListedBatch
};