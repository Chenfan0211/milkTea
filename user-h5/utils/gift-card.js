const config = require('../config');

const DEFAULT_GIFT_CARD_NAME = '礼品卡';
const DEFAULT_GIFT_CARD_IMAGE = '/assets/images/3x/gift-card-matcha.jpg';

function pickGiftCardRecords(source) {
  if (Array.isArray(source)) return source;
  if (source && Array.isArray(source.records)) return source.records;
  return [];
}

function readText(source, key) {
  const value = source && source[key];
  if (value === undefined || value === null) return '';
  return String(value).trim();
}

/**
 * 后端上传图片返回 /api/v1/files/public/... 相对地址；
 * 小程序 image 组件需要完整 URL。本地 /assets 资源继续保持相对路径。
 */
function resolveGiftCardImageUrl(value) {
  const image = value === undefined || value === null ? '' : String(value).trim();
  if (!image) return '';
  if (/^(https?:)?\/\//i.test(image) || /^(data|blob|wxfile|file):/i.test(image)) {
    return image;
  }
  if (image.indexOf('/api/') === 0) {
    const baseUrl = String((config && config.BASE_URL) || '').replace(/\/+$/, '');
    return baseUrl ? baseUrl + image : image;
  }
  return image;
}

function resolveGiftCardDisplay(card, denomination) {
  const direct = card || {};
  const fallback = denomination || {};
  const image =
    readText(direct, 'cardImage') ||
    readText(direct, 'image') ||
    readText(fallback, 'cardImage') ||
    readText(fallback, 'image') ||
    DEFAULT_GIFT_CARD_IMAGE;
  return {
    // 历史卡面下架 / 面额软删后，卡记录自带元数据仍是权威来源。
    name:
      readText(direct, 'cardName') ||
      readText(direct, 'name') ||
      readText(fallback, 'cardName') ||
      readText(fallback, 'name') ||
      DEFAULT_GIFT_CARD_NAME,
    image: resolveGiftCardImageUrl(image)
  };
}

function hasGiftCardDisplayMetadata(item) {
  const source = item || {};
  return Boolean(
    (readText(source, 'cardName') || readText(source, 'name')) &&
      (readText(source, 'cardImage') || readText(source, 'image'))
  );
}

function needsGiftCardDenominations(items) {
  const source = Array.isArray(items) ? items : [];
  return source.some(item => !hasGiftCardDisplayMetadata(item));
}

function buildGiftCardDenominationMap(denominations) {
  const map = {};
  (Array.isArray(denominations) ? denominations : []).forEach(item => {
    if (item && item.id !== undefined && item.id !== null) map[item.id] = item;
  });
  return map;
}

function queryText(value) {
  return value === undefined || value === null ? '' : String(value).trim();
}

/**
 * 卡面由 groupId + cardName 唯一标识。
 * id 参数保留给旧链接兼容：旧版本传的是卡面名称，也可能是真实面额主键。
 */
function selectGiftCardDenominations(source, selection) {
  const list = Array.isArray(source) ? source : [];
  const options = selection || {};
  const groupId = queryText(options.groupId);
  const cardName = queryText(options.cardName);
  const legacyId = queryText(options.id || options.faceId);
  if (!groupId && !cardName && !legacyId) return list;

  let result = list;
  if (groupId) {
    result = result.filter(item => queryText(item && item.groupId) === groupId);
  }
  if (cardName) {
    return result.filter(item => queryText(item && (item.cardName || item.name)) === cardName);
  }
  if (!legacyId) return result;

  const legacyCardMatches = result.filter(
    item => queryText(item && (item.cardName || item.name)) === legacyId
  );
  if (legacyCardMatches.length) return legacyCardMatches;

  return result.filter(
    item => queryText(item && item.id) === legacyId || queryText(item && item.code) === legacyId
  );
}

module.exports = {
  DEFAULT_GIFT_CARD_NAME,
  DEFAULT_GIFT_CARD_IMAGE,
  pickGiftCardRecords,
  resolveGiftCardImageUrl,
  resolveGiftCardDisplay,
  hasGiftCardDisplayMetadata,
  needsGiftCardDenominations,
  buildGiftCardDenominationMap,
  selectGiftCardDenominations
};