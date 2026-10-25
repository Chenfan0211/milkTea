/**
 * 商品推荐「一键加购」纯函数模块。
 *
 * 页面只负责定位与请求编排，门店可售探测、优先级排序与购物车条目构建都放在这里，
 * 便于用 Node 断言脚本直接回归（见 scripts/tongue-recommendation-cart.test.mjs）。
 *
 * 关键约定：探测门店菜单必须直接调用 api.fetchMenu(storeSubjectId)，
 * 不能复用 utils/product-listing 的 refreshMenuFromRemote —— 后者会覆盖
 * 当前门店的全局菜单镜像，导致点单页串店。
 */
const { applyStoreListing, buildCartItem, mergeCartItem } = require('./cart');
const { buildSpecState, normalizeSpecProduct } = require('./spec-sheet');

const RECOMMENDATION_RADIUS_KM = 5;
const DEFAULT_CONCURRENCY = 3;

/** 会话级可售探测缓存：key = storeSubjectId::productIds（排序后）。 */
const availabilityCache = new Map();

function clearAvailabilityCache() {
  availabilityCache.clear();
}

/** 推荐请求条目：只保留已匹配商城、可用于加购的商品。 */
function normalizeRequestItems(products) {
  const seen = new Set();
  return (products || [])
    .filter(product => product && product.productId)
    .map(product => {
      const item = {
        productId: String(product.productId),
        name: product.name || '',
        image: product.image || '',
        reason: product.reason || ''
      };
      if (Array.isArray(product.selectedOptionIds)) {
        item.selectedOptionIds = product.selectedOptionIds.map(String).filter(Boolean);
      }
      if (product.quantity != null) item.quantity = Math.max(1, Number(product.quantity) || 1);
      if (product.priceMode === 'sale') item.priceMode = 'sale';
      if (product.cartItemId) item.cartItemId = String(product.cartItemId);
      return item;
    })
    .filter(item => {
      // 报告可能把同一个商城商品匹配到多条第三方推荐，整区加购只加一次。
      if (seen.has(item.productId)) return false;
      seen.add(item.productId);
      return true;
    });
}

/** 拍平 tab/group/category 三层菜单，返回全部商品。 */
function flattenMenuProducts(menuTabs) {
  const list = [];
  (menuTabs || []).forEach(tab => {
    (tab.groups || []).forEach(group => {
      (group.categories || []).forEach(category => {
        (category.products || []).forEach(product => list.push(product));
      });
    });
  });
  return list;
}

function cacheKeyOf(store, productIds) {
  const storeKey = store && store.subjectId != null ? store.subjectId : store && store.id;
  return String(storeKey) + '::' + productIds.slice().sort().join(',');
}

/** 单店探测：命中返回 all/missing，请求失败返回 unknown（不能误判为缺货）。 */
function probeOneStore(store, productIds, fetchMenu) {
  const totalCount = (productIds || []).length;
  const unknownResult = () => ({
    storeId: store.id,
    storeSubjectId: store.subjectId == null ? null : store.subjectId,
    status: 'unknown',
    availableCount: null,
    totalCount,
    missingProductIds: [],
    menuTabs: []
  });
  if (store.subjectId == null) {
    // 无门店主体编号时不能调用 /menu（不传门店会返回全量菜单，误判为全有售）。
    return Promise.resolve(unknownResult());
  }
  return Promise.resolve()
    .then(() => fetchMenu(store.subjectId))
    .then(menuTabs => {
      const present = new Set(flattenMenuProducts(menuTabs).map(product => String(product.id)));
      const missingProductIds = productIds.filter(productId => !present.has(String(productId)));
      return {
        storeId: store.id,
        storeSubjectId: store.subjectId,
        status: missingProductIds.length ? 'missing' : 'all',
        availableCount: totalCount - missingProductIds.length,
        totalCount,
        missingProductIds,
        menuTabs: Array.isArray(menuTabs) ? menuTabs : []
      };
    })
    .catch(() => unknownResult());
}

/** 固定并发执行任务，避免 5 公里内门店过多时一次性打满请求。 */
function runWithConcurrency(tasks, limit) {
  const list = tasks || [];
  const size = Math.max(1, Number(limit) || DEFAULT_CONCURRENCY);
  const results = new Array(list.length);
  let cursor = 0;
  const worker = () => {
    const index = cursor;
    cursor += 1;
    if (index >= list.length) return Promise.resolve(null);
    return Promise.resolve()
      .then(() => list[index]())
      .then(result => {
        results[index] = result;
        return worker();
      });
  };
  const workers = [];
  for (let i = 0; i < Math.min(size, list.length); i += 1) workers.push(worker());
  return Promise.all(workers).then(() => results);
}

/**
 * 探测门店对推荐商品的可售情况。
 *
 * @param {Array<{id:string, subjectId:number}>} stores 候选门店
 * @param {Array<string>} productIds 推荐商品编号
 * @param {{fetchMenu:Function, concurrency?:number, cache?:Map}} options
 * @returns {Promise<Map<string, {status:'all'|'missing'|'unknown', availableCount:number|null, totalCount:number, missingProductIds:string[], menuTabs:Array}>>}
 */
function probeStoreAvailability(stores, productIds, options = {}) {
  const ids = (productIds || []).map(String).filter(Boolean);
  const fetchMenu = options.fetchMenu;
  if (typeof fetchMenu !== 'function') {
    return Promise.reject(new Error('probeStoreAvailability 需要传入 fetchMenu'));
  }
  if (!ids.length) return Promise.resolve(new Map());
  const cache = options.cache instanceof Map ? options.cache : availabilityCache;
  const concurrency = Math.max(1, Number(options.concurrency) || DEFAULT_CONCURRENCY);
  const resultMap = new Map();
  const pending = [];

  (stores || []).forEach(store => {
    if (!store) return;
    const key = cacheKeyOf(store, ids);
    if (cache.has(key)) {
      resultMap.set(store.id, cache.get(key));
      return;
    }
    pending.push(() =>
      probeOneStore(store, ids, fetchMenu).then(result => {
        cache.set(key, result);
        resultMap.set(store.id, result);
        return result;
      })
    );
  });

  return runWithConcurrency(pending, concurrency).then(() => resultMap);
}

function badgeOf(store, radiusKm) {
  const withinRadius = Number.isFinite(Number(store.distanceValue)) && Number(store.distanceValue) <= radiusKm;
  if (!withinRadius) return null;
  const totalValue = store.recommendationTotal;
  const availableValue = store.recommendationAvailableCount;
  const total = Number(totalValue);
  const available = Number(availableValue);
  if (availableValue === null || availableValue === undefined || !Number.isFinite(available)) {
    return store.recommendationStatus === 'unknown' ? { text: '可售待确认', tone: 'muted' } : null;
  }
  if (available <= 0) {
    return { text: total ? '推荐商品 0/' + total : '推荐商品 0 件', tone: 'muted' };
  }
  return {
    text: '推荐商品 ' + available + '/' + total + ' 有售',
    tone: available >= total ? 'success' : 'warning'
  };
}

/**
 * 给门店补推荐可售标注并排序：
 * 第一组 = 距离 ≤ radiusKm 且至少 1 件推荐商品有售，按可售数量降序、距离升序；
 * 第二组 = 其余（含 radiusKm 内 0 件有售与半径外门店），按距离升序。
 */
function annotateAndSortStores(stores, availability, radiusKm = RECOMMENDATION_RADIUS_KM) {
  const radius = Number(radiusKm) > 0 ? Number(radiusKm) : RECOMMENDATION_RADIUS_KM;
  const annotated = (stores || []).map(store => {
    const record = availability && typeof availability.get === 'function' ? availability.get(store.id) : null;
    const status = record ? record.status : '';
    const hasAvailable = record && record.availableCount !== null && record.availableCount !== undefined;
    const hasTotal = record && record.totalCount !== null && record.totalCount !== undefined;
    const availableCount =
      hasAvailable && Number.isFinite(Number(record.availableCount)) ? Number(record.availableCount) : null;
    const totalCount = hasTotal && Number.isFinite(Number(record.totalCount)) ? Number(record.totalCount) : null;
    const missingCount = record && record.missingProductIds ? record.missingProductIds.length : 0;
    const distanceValue = Number(store.distanceValue);
    const withinRadius = Number.isFinite(distanceValue) && distanceValue <= radius;
    const priority = withinRadius && availableCount !== null && availableCount > 0;
    const next = Object.assign({}, store, {
      recommendationStatus: status,
      recommendationAvailableCount: availableCount,
      recommendationTotal: totalCount,
      recommendationMissingCount: missingCount,
      recommendationPriority: priority
    });
    next.recommendationBadge = badgeOf(next, radius);
    return next;
  });

  return annotated.sort((left, right) => {
    if (left.recommendationPriority !== right.recommendationPriority) {
      return left.recommendationPriority ? -1 : 1;
    }
    if (left.recommendationPriority) {
      const leftAvailable = Number(left.recommendationAvailableCount) || 0;
      const rightAvailable = Number(right.recommendationAvailableCount) || 0;
      if (leftAvailable !== rightAvailable) return rightAvailable - leftAvailable;
    }
    const leftDistance = Number(left.distanceValue);
    const rightDistance = Number(right.distanceValue);
    const leftValue = Number.isFinite(leftDistance) ? leftDistance : Infinity;
    const rightValue = Number.isFinite(rightDistance) ? rightDistance : Infinity;
    return leftValue - rightValue;
  });
}

/**
 * 用门店菜单构建一键加购条目。
 *
 * 有售商品按「默认规格」加入：优先 option.selected，否则取该规格组第一个选项；
 * 缺货商品进入 missing，由页面提示「已下架」。
 */
function buildRecommendationCartItems(menuTabs, requestItems) {
  const products = flattenMenuProducts(menuTabs);
  const byId = new Map(products.map(product => [String(product.id), product]));
  const items = [];
  const missing = [];
  const available = [];

  (requestItems || []).forEach(request => {
    const product = byId.get(String(request.productId));
    if (!product) {
      missing.push({ productId: request.productId, name: request.name || '' });
      return;
    }
    const normalized = normalizeSpecProduct(product);
    const state = buildSpecState(normalized, {});
    const item = buildCartItem({
      product: normalized,
      selectedOptions: state.selectedOptions,
      quantity: 1,
      unitPrice: state.memberPrice,
      originalPrice: state.originalPrice,
      storedValuePrice: state.storedValuePrice,
      storedValueDiscount: state.storedValueDiscount,
      specText: state.specText
    });
    items.push(item);
    available.push({ productId: item.productId, name: item.name });
  });

  return { items, missing, available };
}

/**
 * 用门店菜单重建“立即购买 / 详情购物车”条目。
 *
 * 与一键加购不同，这里必须保留用户在详情页选择的规格与数量，
 * 价格则重新按当前门店菜单计算，避免使用详情页加载时的旧价格。
 */
function buildSelectedRecommendationCartItems(menuTabs, requestItems) {
  const products = flattenMenuProducts(menuTabs);
  const byId = new Map(products.map(product => [String(product.id), product]));
  const items = [];
  const missing = [];
  const available = [];

  (requestItems || []).forEach(request => {
    const product = byId.get(String(request.productId));
    if (!product) {
      missing.push({
        productId: request.productId,
        name: request.name || '',
        cartItemId: request.cartItemId || ''
      });
      return;
    }
    const normalized = normalizeSpecProduct(product);
    const state = buildSpecState(normalized, {
      initialSelectedOptionIds: request.selectedOptionIds || [],
      initialQuantity: request.quantity || 1,
      priceMode: request.priceMode === 'sale' ? 'sale' : ''
    });
    const item = buildCartItem({
      product: normalized,
      selectedOptions: state.selectedOptions,
      quantity: state.quantity,
      unitPrice: state.memberPrice,
      originalPrice: state.originalPrice,
      storedValuePrice: state.storedValuePrice,
      storedValueDiscount: state.storedValueDiscount,
      specText: state.specText,
      priceMode: state.priceMode
    });
    item.cartItemId = request.cartItemId || '';
    items.push(item);
    available.push({ productId: item.productId, name: item.name });
  });

  return { items, missing, available };
}
/** 把一键加购条目合并进现有购物车（同规格累加数量）。 */
function mergeRecommendationItems(cartItems, items) {
  return (items || []).reduce(
    (list, item) => mergeCartItem(list, item),
    (cartItems || []).map(item => Object.assign({}, item))
  );
}

module.exports = {
  DEFAULT_CONCURRENCY,
  RECOMMENDATION_RADIUS_KM,
  annotateAndSortStores,
  applyStoreListing,
  buildRecommendationCartItems,
  buildSelectedRecommendationCartItems,
  clearAvailabilityCache,
  flattenMenuProducts,
  mergeRecommendationItems,
  normalizeRequestItems,
  probeStoreAvailability
};
