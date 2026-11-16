const DEFAULT_HOME_BANNERS = [
  {
    id: 'default-home-hero',
    imageUrl: '/assets/images/3x/home-hero.jpg',
    altText: '金秋入茶限定上新',
    targetType: 'none',
    targetId: ''
  }
];

const { normalizeTarget } = require('./banner-target');

function normalizeHomeBanners(config, resolveAssetUrl) {
  const hero = config && typeof config === 'object' ? config : {};
  const list = Array.isArray(hero.banners)
    ? hero.banners
    : hero.imageUrl
      ? [{ id: 'legacy', imageUrl: hero.imageUrl, altText: hero.altText }]
      : [];
  const resolve = typeof resolveAssetUrl === 'function' ? resolveAssetUrl : value => value;
  const banners = list
    .map((item, index) => {
      if (item?.status === 'disabled' || item?.deleted === true || item?.deleted === 1 || item?.deleted === '1')
        return null;
      const imageUrl = typeof item?.imageUrl === 'string' ? item.imageUrl.trim() : '';
      if (!imageUrl) return null;
      return {
        id: String(item.id || `banner-${index}`),
        imageUrl: resolve(imageUrl),
        altText: String(item.altText || '').trim() || '首页活动图',
        ...normalizeTarget(item)
      };
    })
    .filter(Boolean);
  return banners.length ? banners : DEFAULT_HOME_BANNERS.map(item => ({ ...item }));
}

function removeFailedHomeBanner(list, index) {
  const remaining = (Array.isArray(list) ? list : []).filter((_, i) => i !== Number(index));
  return remaining.length ? remaining : DEFAULT_HOME_BANNERS.map(item => ({ ...item }));
}

module.exports = { DEFAULT_HOME_BANNERS, normalizeHomeBanners, removeFailedHomeBanner };
