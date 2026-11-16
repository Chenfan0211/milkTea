const TARGET_TYPES = new Set(['none', 'orders', 'coupons', 'recharge', 'menu', 'menuProduct', 'mallProduct']);

function normalizeTarget(value) {
  const type = value && TARGET_TYPES.has(value.targetType) ? value.targetType : 'none';
  const id = value && value.targetId != null ? String(value.targetId).trim() : '';
  if ((type === 'menuProduct' || type === 'mallProduct') && !id) return { targetType: 'none', targetId: '' };
  if (type === 'mallProduct' && !/^[1-9]\d*$/.test(id)) return { targetType: 'none', targetId: '' };
  return { targetType: type, targetId: type === 'menuProduct' || type === 'mallProduct' ? id : '' };
}

function isMallProductAvailable(product) {
  return Boolean(product && product.id && product.soldOut !== true && Number(product.availableStock) > 0);
}

function findMenuProduct(menus, productId) {
  for (const tab of Array.isArray(menus) ? menus : []) {
    for (const group of tab.groups || []) {
      for (const category of group.categories || []) {
        const found = (category.products || []).find(product => String(product.id) === String(productId));
        if (found) return found;
      }
    }
  }
  return null;
}

function pickPointStore(catalog) {
  if (!catalog) return null;
  if (catalog.location && catalog.location.source !== 'default') {
    return (catalog.stores || []).slice().sort((a, b) => Number(a.distanceValue) - Number(b.distanceValue))[0] || null;
  }
  return catalog.currentStore || (catalog.stores || [])[0] || null;
}

function openBannerTarget(item) {
  const { targetType, targetId } = normalizeTarget(item);
  if (targetType === 'none') return Promise.resolve();
  const navigate = require('./navigate');
  if (targetType === 'menu') return Promise.resolve(navigate.open('/pages/menu/menu'));
  if (targetType === 'orders' || targetType === 'coupons' || targetType === 'recharge') {
    const routes = {
      orders: '/pages/orders/orders',
      coupons: '/pages/coupon-list/coupon-list',
      recharge: '/pages/stored-value/stored-value'
    };
    return require('./login-guard').requireLogin(() => navigate.open(routes[targetType]), {
      reason: '登录后可查看相关功能'
    });
  }
  if (targetType === 'mallProduct') {
    return require('./api')
      .fetchMallProductDetail(Number(targetId))
      .then(detail => {
        if (isMallProductAvailable(detail)) {
          navigate.open(`/packageMall/pages/product-detail/product-detail?id=${Number(targetId)}`);
        } else {
          navigate.open('/packageMall/pages/home/home');
          wx.showToast({ title: '商品已售罄，看看其他商品', icon: 'none' });
        }
      })
      .catch(() => {
        navigate.open('/packageMall/pages/home/home');
        wx.showToast({ title: '商品已下架或暂不可用', icon: 'none' });
      });
  }
  const store = require('./store');
  return Promise.all([store.refreshCitiesFromRemote(), store.refreshStoreCatalogFromRemote()]).then(() => {
    const picked = pickPointStore(store.resolveStoreCatalog());
    if (picked) store.selectStore(picked.id);
    getApp().globalData.pendingBannerProductId = picked ? targetId : null;
    navigate.open('/pages/menu/menu');
    if (!picked) wx.showToast({ title: '请先选择门店', icon: 'none' });
  });
}

module.exports = { normalizeTarget, isMallProductAvailable, findMenuProduct, pickPointStore, openBannerTarget };
