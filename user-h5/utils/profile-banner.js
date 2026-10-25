const DEFAULT_PROFILE_BANNERS = [
  {
    id: 'default-profile-banner',
    imageUrl: '/assets/images/3x/profile-banner.jpg',
    altText: '抹茶轻茉莉新品'
  }
];

const DEFAULT_ALT_TEXT = '抹茶轻茉莉新品';

function cloneDefaults() {
  return DEFAULT_PROFILE_BANNERS.map(item => Object.assign({}, item));
}

function normalizeBanners(list, resolveAssetUrl) {
  if (!Array.isArray(list)) return cloneDefaults();
  const resolve = typeof resolveAssetUrl === 'function' ? resolveAssetUrl : value => value;
  return list
    .map((item, index) => {
      const source = item && typeof item === 'object' ? item : {};
      const imageUrl = typeof source.imageUrl === 'string' ? source.imageUrl.trim() : '';
      if (!imageUrl) return null;
      const altText =
        typeof source.altText === 'string' && source.altText.trim() ? source.altText.trim() : DEFAULT_ALT_TEXT;
      return {
        id: String(source.id == null ? `banner-${index}` : source.id),
        imageUrl: resolve(imageUrl),
        altText
      };
    })
    .filter(Boolean);
}

function removeFailedBanner(list, index) {
  const source = Array.isArray(list) ? list : [];
  const next = source.filter((item, itemIndex) => itemIndex !== Number(index));
  if (next.length) return next;
  const only = source[0];
  if (source.length === 1 && only && only.id === DEFAULT_PROFILE_BANNERS[0].id) return [];
  return cloneDefaults();
}

module.exports = {
  DEFAULT_PROFILE_BANNERS,
  normalizeBanners,
  removeFailedBanner
};
