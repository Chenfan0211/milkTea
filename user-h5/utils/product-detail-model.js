/**
 * 消费端商品详情渲染模型（纯函数，无 wx 依赖）。
 *
 * 页面只负责请求与状态切换；金额从「分」统一换算为「元」，
 * 规格结构复用 utils/spec-sheet 的 normalizeSpecProduct，保证与点单页一致。
 */
const { normalizeSpecProduct } = require('./spec-sheet');
const { formatYuan } = require('./money');

/** 纯文本兜底：详情字段可能来自历史脏数据。 */
function plainText(value) {
  const raw = value === undefined || value === null ? '' : String(value);
  if (!raw) return '';
  return raw
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;|&#160;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

function buildProductDetailViewModel(detail) {
  const product = normalizeSpecProduct(detail || {});
  const spec = product.specDetail || {};
  const price = Number(product.price) || 0;
  const originalPrice = Number(product.originalPrice) || 0;
  return {
    id: product.id || '',
    name: plainText(product.name),
    image: spec.galleryImage || product.image || '',
    imageDisclaimer: plainText(spec.imageDisclaimer),
    priceLabel: plainText(spec.priceLabel),
    priceText: '¥' + formatYuan(price),
    originalPriceText: originalPrice > price ? '¥' + formatYuan(originalPrice) : '',
    promotionText: plainText(spec.promotionText),
    description: plainText(spec.description),
    ingredients: plainText(spec.ingredients),
    allergens: plainText(spec.allergens),
    cupCapacity: plainText(spec.cupCapacity),
    tips: (spec.tips || []).map(plainText).filter(Boolean),
    specGroups: (spec.specGroups || [])
      .map(group => ({
        id: String(group.id || group.label || ''),
        label: plainText(group.label),
        options: (group.options || []).map(option => ({
          id: String(option.id || option.label || ''),
          label: plainText(option.label),
          priceDeltaText: Number(option.priceDelta) > 0 ? '¥' + formatYuan(option.priceDelta) : ''
        }))
      }))
      .filter(group => group.label || group.options.length),
    tags: (product.tags || []).map(plainText).filter(Boolean)
  };
}

module.exports = {
  buildProductDetailViewModel,
  plainText
};
