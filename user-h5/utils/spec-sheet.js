const { calcMemberPrice } = require('./member-level');
const { getUserProfile } = require('./user-profile');

function roundMoney(value, digits = 2) {
  const factor = Math.pow(10, digits);
  return Math.round(value * factor) / factor;
}

function cloneSpecGroups(groups = []) {
  return groups.map(group => ({
    ...group,
    options: group.options.map(option => ({ ...option }))
  }));
}

/**
 * 后端金额单位为「分」，前端按「元」展示。
 * 兼容两种来源：后端 /api/v1/app/menu（分为单位）与已换算过的元数据。
 */
function fromCents(value) {
  const num = Number(value);
  if (!Number.isFinite(num)) return 0;
  return roundMoney(num / 100);
}

/**
 * 把后端 /api/v1/app/menu 下发的扁平商品字段适配为 spec-sheet 需要的 specDetail 结构。
 *
 * 后端 MenuDTO.MenuProduct 使用扁平字段（ingredients / specGroups / specTag），
 * 而规格弹层 WXML 读取的是两层结构 product.specDetail.*，层级不一致会导致弹层空白，
 * 因此这里做一次幂等的归一化。
 */
function normalizeSpecProduct(product = {}) {
  const source = product || {};
  if (!source.id) return source;
  if (source.specDetail) return source;

  const price = source.price == null ? 0 : fromCents(source.price);
  const originalPrice = source.originalPrice == null ? price : fromCents(source.originalPrice);
  const storedValuePrice = source.storedValuePrice == null ? 0 : fromCents(source.storedValuePrice);

  const specDetail = {
    galleryImage: source.galleryImage || source.image || '',
    imageDisclaimer: source.imageDisclaimer || '',
    promotionText: source.promotionText || '',
    priceLabel: source.priceLabel || '会员价',
    startPrice: price,
    originalBasePrice: originalPrice,
    storedValuePrice,
    tag: source.specTag || '',
    description: source.description || '',
    ingredients: source.ingredients || '',
    allergens: source.allergens || '',
    cupCapacity: source.cupCapacity || '',
    tips: Array.isArray(source.tips) ? source.tips.slice() : [],
    specGroups: (source.specGroups || []).map(group => ({
      id: group.id,
      label: group.label,
      options: (group.options || []).map(option => ({
        id: option.id,
        label: option.label,
        priceDelta: option.priceDelta ? fromCents(option.priceDelta) : 0,
        selected: Boolean(option.selected),
        icon: option.icon
      }))
    }))
  };

  return Object.assign({}, source, {
    price,
    originalPrice,
    storedValuePrice,
    specDetail
  });
}

function buildSpecState(product = {}, options = {}) {
  const detail = product.specDetail || {};
  const specGroups = cloneSpecGroups(detail.specGroups);
  const selectedIds = options.initialSelectedOptionIds || [];
  const selectedOptions = [];
  const selectedLabels = [];

  for (const group of specGroups) {
    const selected =
      group.options.find(option => selectedIds.includes(option.id)) || group.options.find(option => option.selected);

    group.options = group.options.map(option =>
      Object.assign({}, option, {
        selected: Boolean(selected && selected.id === option.id)
      })
    );

    if (selected) {
      selectedOptions.push({ groupId: group.id, ...selected });
      selectedLabels.push(selected.label);
    }
  }

  const addOnTotal = selectedOptions.reduce((sum, option) => sum + (option.priceDelta || 0), 0);
  const listPrice = detail.originalBasePrice || detail.startPrice || product.originalPrice || product.price || 0;
  const vipLevel = getUserProfile().vipLevel || '';
  const memberBasePrice = calcMemberPrice(listPrice, vipLevel);
  const memberPrice = roundMoney(memberBasePrice + addOnTotal);
  const originalPrice = roundMoney(listPrice + addOnTotal);
  const storedValueDiscount = roundMoney(detail.storedValuePrice || product.storedValuePrice || 0);
  const storedValuePrice = roundMoney(memberPrice - storedValueDiscount);
  const specText = selectedLabels.join(',');

  return {
    expanded: false,
    quantity: Math.max(1, Number(options.initialQuantity) || 1),
    favorite: false,
    showTasteTip: options.mode !== 'edit',
    specGroups,
    selectedOptions,
    memberPrice,
    originalPrice,
    storedValuePrice,
    storedValueDiscount,
    specText
  };
}

module.exports = {
  buildSpecState,
  normalizeSpecProduct,
  roundMoney
};
