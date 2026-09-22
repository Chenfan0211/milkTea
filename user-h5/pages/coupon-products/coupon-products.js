const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getStoreById, resolveStoreCatalog } = require('../../utils/store');
const { getListedMenuTabs } = require('../../utils/product-listing');

function getSizeText(product) {
  const specGroups = product && Array.isArray(product.specGroups) ? product.specGroups : [];
  const sizeGroup = specGroups.find(group => group.id === 'size');
  const labels =
    sizeGroup && Array.isArray(sizeGroup.options) ? sizeGroup.options.map(option => option.label).filter(Boolean) : [];
  return labels.length ? labels.join('、') : '中杯';
}

function buildProductIndex(menus) {
  const index = {};
  menus.forEach(menu => {
    menu.groups.forEach(group => {
      group.categories.forEach(category => {
        category.products.forEach(product => {
          index[product.id] = product;
        });
      });
    });
  });
  return index;
}

// 适用商品需与点单页保持一致：已下架商品不再展示。
function resolveProducts(coupon, storeId) {
  if (!coupon || !Array.isArray(coupon.applicableProductIds)) return [];
  const productIndex = buildProductIndex(getListedMenuTabs(storeId));
  const seenNames = [];
  const products = [];

  coupon.applicableProductIds.forEach(productId => {
    const product = productIndex[productId];
    if (!product || seenNames.indexOf(product.name) !== -1) return;
    seenNames.push(product.name);
    products.push(Object.assign({}, product, { sizeText: getSizeText(product) }));
  });

  return products;
}

function resolveStore(storeId) {
  const selectedStore = getStoreById(storeId);
  if (selectedStore) return selectedStore;
  const catalog = resolveStoreCatalog();
  return catalog.currentStore || catalog.stores[0] || {};
}

Page(
  withShare({
    data: {
      storeName: '适用门店',
      products: []
    },
    onLoad(options = {}) {
      // 优惠券来自后端；拿到券模板后再解析适用商品
      const store = resolveStore(options.storeId);
      api
        .fetchCoupons()
        .then(list => (Array.isArray(list) ? list : []))
        .catch(() => [])
        .then(list => {
          const coupon = list.find(item => (item.code || item.id) === options.couponId);
          this.setData({
            storeName: store.name || '适用门店',
            products: resolveProducts(coupon, store.id)
          });
        });
    }
  })
);

