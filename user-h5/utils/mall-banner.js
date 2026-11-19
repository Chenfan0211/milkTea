const TARGET_TYPES = new Set(['none', 'mallProduct']);

function normalizeTarget(item) {
  const type = item && TARGET_TYPES.has(item.targetType) ? item.targetType : 'none';
  const id = item && item.targetId != null ? String(item.targetId).trim() : '';
  if (type === 'mallProduct' && /^[1-9]\d*$/.test(id)) {
    return { targetType: type, targetId: id };
  }
  return { targetType: 'none', targetId: '' };
}

function normalizeBanners(config, resolveAssetUrl) {
  const source = Array.isArray(config) ? config : config && Array.isArray(config.banners) ? config.banners : [];
  const resolve = typeof resolveAssetUrl === 'function' ? resolveAssetUrl : value => value;
  return source
    .map((item, index) => {
      const imageUrl = typeof item?.imageUrl === 'string' ? item.imageUrl.trim() : '';
      if (!imageUrl) return null;
      return {
        id: String(item.id || `mall-banner-${index}`),
        imageUrl: resolve(imageUrl),
        altText: String(item.altText || '').trim() || '商城活动图',
        ...normalizeTarget(item)
      };
    })
    .filter(Boolean);
}

function removeFailedBanner(list, index) {
  return (Array.isArray(list) ? list : []).filter((_, itemIndex) => itemIndex !== Number(index));
}

function isMallProductAvailable(product) {
  return Boolean(product && product.id && product.soldOut !== true && Number(product.availableStock) > 0);
}

function openBannerTarget(item, dependencies) {
  const target = normalizeTarget(item);
  if (target.targetType === 'none') return Promise.resolve('noop');

  return dependencies
    .fetchMallProductDetail(Number(target.targetId))
    .then(detail => {
      if (isMallProductAvailable(detail)) {
        dependencies.navigateTo(`/packageMall/pages/product-detail/product-detail?id=${Number(target.targetId)}`);
        return 'opened';
      }
      dependencies.showToast({ title: '商品已售罄，看看其他商品', icon: 'none' });
      return 'unavailable';
    })
    .catch(() => {
      dependencies.showToast({ title: '商品已下架或暂不可用', icon: 'none' });
      return 'unavailable';
    });
}

module.exports = {
  normalizeBanners,
  removeFailedBanner,
  openBannerTarget
};
