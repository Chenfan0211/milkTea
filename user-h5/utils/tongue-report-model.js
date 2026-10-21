/**
 * 舌诊详情页渲染模型（纯函数，无 wx 依赖）。
 *
 * 抽成独立模块的原因：第三方分级分组 → 五个 Tab → 块/子块 的映射决定了参考图能否还原，
 * 必须能直接拿真实第三方报文在 Node 里回归（见 scripts/tongue-report-model.test.mjs）。
 */
const { resolveAssetUrl } = require('./asset-url');
const { formatDateTime } = require('./date-format');

const MAX_PRODUCT_TAGS = 2;
const MAX_PRODUCT_CONSTITUTIONS = 4;

/** 详情页五个固定 Tab，与后端 tabKey 一一对应。 */
const TAB_DEFS = [
  { key: 'overview', label: '概况' },
  { key: 'diet', label: '饮食' },
  { key: 'acupoint', label: '穴位' },
  { key: 'emotion', label: '情志' },
  { key: 'sport', label: '运动' }
];

/** 这些章节的 descriptions 在参考图里是标签云，不是「标题 + 正文」。 */
const CHIP_TITLES = ['易发疾病', '常见体征'];

const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

/** 二十四节气：四季节气进补下按节气切换。 */
const SEASONS = [
  '立春',
  '雨水',
  '惊蛰',
  '春分',
  '清明',
  '谷雨',
  '立夏',
  '小满',
  '芒种',
  '夏至',
  '小暑',
  '大暑',
  '立秋',
  '处暑',
  '白露',
  '秋分',
  '寒露',
  '霜降',
  '立冬',
  '小雪',
  '大雪',
  '冬至',
  '小寒',
  '大寒'
];

/** 商品区块标题（参考图 6「养生饮茶」）。 */
const PRODUCT_BLOCK_TITLE = '养生饮茶';
/** 商品区块插在「饮食方式」之后，与参考图顺序一致；没有该章节时放最前。 */
const PRODUCT_BLOCK_ANCHOR = '饮食方式';

function yuanLabel(fen) {
  const value = Number(fen);
  if (!Number.isFinite(value)) return '';
  const yuan = (value / 100).toFixed(2).replace(/\.?0+$/, '');
  return '¥' + yuan;
}

/**
 * 纯文本兜底：后端已统一清洗，这里再兜一层，
 * 防止旧报文/旧缓存把 <p>、&nbsp; 当成可见文字渲染出来。
 */
function toPlainText(value) {
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

/** descriptions → { title, text }；text 一定是纯文本。 */
function toDescriptions(items) {
  return (items || [])
    .map(item => ({
      title: toPlainText(item && item.title),
      text: toPlainText(item && item.content)
    }))
    .filter(item => item.title || item.text);
}

/** 子节点自身没有 descriptions 时，把它的一层子节点压成「标题 + 正文」对。 */
function collapseDescriptions(node) {
  if (node.descriptions.length) return node.descriptions;
  return (node.children || [])
    .map(child => ({
      title: child.title,
      text:
        toPlainText(child.content) ||
        toDescriptions(child.descriptions)
          .map(item => [item.title, item.text].filter(Boolean).join(' '))
          .join(' ')
    }))
    .filter(item => item.title || item.text);
}

/**
 * 头部「男/23岁」：只使用该次检测的接口值（后端已下发 sex/age）。
 * 老记录缺失时返回空串，页面只显示体质名；不再回落到本地缓存资料。
 */
function profileSummaryOf(report) {
  const parts = [];
  const sex = String((report && report.sex) || '').toLowerCase();
  if (sex === 'male') parts.push('男');
  else if (sex === 'female') parts.push('女');
  const age = Number(report && report.age);
  if (Number.isFinite(age) && age >= 0 && age <= 150) parts.push(age + '岁');
  return parts.join('/');
}

function normalizeNode(raw, activeMediaKey) {
  const media = raw.media || null;
  const descriptions = toDescriptions(raw.descriptions);
  const analysis = raw.analysis || null;
  const title = raw.title || '';
  const isChip = CHIP_TITLES.indexOf(title) >= 0;
  const factors = (analysis && analysis.factors) || [];
  const features = (analysis && analysis.features) || [];
  const source = (media && media.source) || '';
  const poster = resolveAssetUrl((media && media.thumbnail) || '');
  const kind = (media && media.kind) || '';
  return {
    id: String(raw.id || title),
    title,
    content: raw.content || '',
    hasContent: Boolean(raw.content),
    isChip,
    chips: isChip ? descriptions.map(item => item.title || item.text).filter(Boolean) : [],
    descriptions: isChip ? [] : descriptions,
    hasDescriptions: !isChip && descriptions.length > 0,
    isAnalysis: Boolean(factors.length || features.length),
    factors,
    features,
    media:
      media && (source || poster)
        ? {
            key: String(raw.id || title),
            title,
            kind,
            source,
            poster,
            duration: (media && media.duration) || '',
            active: activeMediaKey === String(raw.id || title)
          }
        : null,
    children: []
  };
}

/**
 * 把扁平章节还原成树：一级分组本身不是章节，因此 parentId 指向分组 id 的节点即根节点。
 */
function buildTree(sections, activeMediaKey) {
  const nodes = (sections || []).map(section => normalizeNode(section, activeMediaKey));
  const byId = {};
  nodes.forEach(node => {
    byId[node.id] = node;
  });
  const roots = [];
  (sections || []).forEach((section, index) => {
    const node = nodes[index];
    const parentId = String(section.parentId || '');
    const parent = parentId ? byId[parentId] : null;
    if (parent && parent !== node) parent.children.push(node);
    else roots.push(node);
  });
  return roots;
}

function pickPills(node) {
  const days = node.children.filter(child => WEEKDAYS.indexOf(child.title) >= 0);
  if (days.length >= 2) return days;
  const seasons = node.children.filter(child => SEASONS.indexOf(child.title) >= 0);
  if (seasons.length >= 2) return seasons;
  return [];
}

/**
 * 一级章节 → 渲染模型。
 *
 * 关键收敛：把树折叠成「最多两层」的结构，WXML 不需要递归模板。
 *  - 有周X / 节气子节点 → 渲染 pill，subBlocks 取当前 pill 的子节点；
 *  - 其它有子节点 → subBlocks 取子节点；
 *  - 没有子节点 → 本节自身即内容块。
 */
function toBlock(node, activeMediaKey, activePill) {
  const block = {
    id: node.id,
    title: node.title,
    chips: node.chips,
    isChip: node.isChip,
    factors: node.factors,
    features: node.features,
    isAnalysis: node.isAnalysis,
    content: node.content,
    hasContent: node.hasContent,
    descriptions: node.descriptions,
    hasDescriptions: node.hasDescriptions,
    media: node.media,
    hasOwnMedia: Boolean(node.media),
    pills: [],
    activePill: '',
    subBlocks: []
  };

  const pills = pickPills(node);
  if (pills.length >= 2) {
    const labels = pills.map(item => item.title);
    const current = labels.indexOf(activePill) >= 0 ? activePill : labels[0];
    const active = pills[labels.indexOf(current)] || pills[0];
    block.pills = labels;
    block.activePill = current;
    block.subBlocks = active.children.length
      ? active.children.map(child => toSubBlock(child, activeMediaKey))
      : [toSubBlock(active, activeMediaKey)];
    block.hasPillContent = pills.some(pill => {
      const children = pill.children.length ? pill.children : [pill];
      return children.some(child => subBlockHasRenderableContent(toSubBlock(child, activeMediaKey)));
    });
    return block;
  }

  if (!node.children.length) return block;

  block.subBlocks = node.children.map(child => toSubBlock(child, activeMediaKey));
  return block;
}

function toSubBlock(node, activeMediaKey) {
  const descriptions = collapseDescriptions(node);
  return {
    id: node.id,
    title: node.title,
    content: node.content,
    hasContent: Boolean(node.content),
    descriptions,
    hasDescriptions: descriptions.length > 0,
    media: node.media
  };
}

/** 子块是否有可渲染内容（只有标题不算）。 */
function subBlockHasRenderableContent(sub) {
  if (!sub) return false;
  if (sub.hasContent && toPlainText(sub.content)) return true;
  if (sub.hasDescriptions) return true;
  if (sub.media) return true;
  return false;
}

/**
 * 分区是否有可渲染内容：
 * 正文/描述/媒体/分析/标签/商品/可渲染子块任一存在即展示；
 * 只有标题、空白 HTML、空子节点的分区必须隐藏（覆盖饮食方式/四季节气进补/一周食谱等）。
 */
function blockHasRenderableContent(block) {
  if (!block) return false;
  if (block.isProducts) return Boolean(block.products && block.products.length);
  if (block.isChip) return Boolean(block.chips && block.chips.length);
  if (block.isAnalysis) return Boolean((block.factors || []).length || (block.features || []).length);
  if (block.hasContent && toPlainText(block.content)) return true;
  if (block.hasDescriptions) return true;
  if (block.media) return true;
  // 节气/周X 切换块：任一 pill 的子内容可渲染就保留整个分区，避免默认 pill 为空时误隐藏。
  if (block.hasPillContent) return true;
  return (block.subBlocks || []).some(subBlockHasRenderableContent);
}

function filterRenderableBlocks(blocks) {
  return (blocks || []).filter(blockHasRenderableContent);
}

/**
 * 推荐理由纯文本：第三方原样下发「【推荐理由】 …」，卡片左标题已固定写「推荐理由：」，
 * 这里去掉可选前缀，避免出现「推荐理由：【推荐理由】」。
 */
function productReason(value) {
  return toPlainText(value)
    .replace(/^【推荐理由】\s*/, '')
    .trim();
}

/**
 * 商品卡：只渲染「报告商品名 ↔ 商城商品名」匹配成功的商品；
 * 未命中直接不显示（参考图口径：匹配不到就不展示商品数据）。
 * 卡片字段：主图 + 名称 + 标签 + 适宜体质 + 价格 + 推荐理由 + 商城商品编号。
 * productId 供一键加购 / 商品详情使用，与菜单商品的 id 同为 product.product_id。
 */
function toProductCards(products) {
  return (products || [])
    .filter(product => product && product.matched)
    .map(product => ({
      id: String(product.productId || product.goodsId || product.goodsName || ''),
      productId: String(product.productId || ''),
      name: product.goodsName || '',
      image: resolveAssetUrl(product.image || (product.media && product.media.thumbnail) || ''),
      price: yuanLabel(product.price),
      tags: (product.tags || []).slice(0, MAX_PRODUCT_TAGS),
      constitutions: (product.suitableConstitutions || []).slice(0, MAX_PRODUCT_CONSTITUTIONS),
      reason: productReason(product.reason)
    }))
    .filter(card => card.name);
}

/**
 * 标题关键字 → Tab 键。
 *
 * 后端会在 reportGroups[].tabKey 下发该映射，但线上版本可能仍是旧接口（不带 tabKey），
 * 此时若一律落到 other，五个固定 Tab 会全空、内容被挤到额外 Tab（历史故障现象）。
 * 因此前端按同一套关键字再兜一层，保证新旧接口都能正确归组。
 */
const TAB_KEYWORDS = [
  ['概况', 'overview'],
  ['概览', 'overview'],
  ['饮食', 'diet'],
  ['穴位', 'acupoint'],
  ['经络', 'acupoint'],
  ['情志', 'emotion'],
  ['心理', 'emotion'],
  ['运动', 'sport'],
  ['健身', 'sport']
];

function tabKeyOfTitle(title, declaredKey) {
  if (declaredKey && declaredKey !== 'other') return declaredKey;
  const value = String(title || '').trim();
  for (const [keyword, key] of TAB_KEYWORDS) {
    if (value.includes(keyword)) return key;
  }
  return 'other';
}

function normalizeGroups(groups) {
  return (groups || []).map(group => ({
    key: tabKeyOfTitle(group.title, group.tabKey),
    title: group.title || '报告',
    products: toProductCards(group.products),
    sections: group.sections || []
  }));
}

function buildTabs(report, activeMediaKey, activePillMap, previousTabs) {
  const groups = normalizeGroups(report && report.reportGroups);
  const previous = {};
  (previousTabs || []).forEach(tab => {
    previous[tab.key] = tab;
  });

  const tabs = TAB_DEFS.map(def => ({ key: def.key, label: def.label, sections: [], products: [] }));
  const extras = [];
  groups.forEach((group, index) => {
    const fixed = tabs.filter(tab => tab.key === group.key)[0];
    const target = fixed || { key: group.key + '-' + index, label: group.title, sections: [], products: [] };
    target.sections = target.sections.concat(group.sections);
    target.products = target.products.concat(group.products);
    if (!fixed) extras.push(target);
  });

  // 检测阶段只回摘要时 reportGroups 为空，此时用顶层 productList 兜底渲染商品卡。
  if (!groups.length) {
    const fallback = toProductCards(report && report.productList);
    if (fallback.length) tabs[1].products = fallback;
  }

  return tabs.concat(extras).map(tab => {
    const pills = (activePillMap || {})[tab.key] || {};
    const keepMedia = previous[tab.key] ? activeMediaKey : '';
    const blocks = buildTree(tab.sections, keepMedia).map(node => toBlock(node, keepMedia, pills[node.id]));
    return {
      key: tab.key,
      label: tab.label,
      sections: tab.sections,
      products: tab.products,
      blocks: filterRenderableBlocks(injectProducts(blocks, tab.products, tab.key === 'diet'))
    };
  });
}

/** 商品区块插到「饮食方式」之后；没有该章节时放最前。 */
function injectProducts(blocks, products, canInject) {
  if (!canInject || !products.length) return blocks;
  const block = {
    id: 'diet-products',
    title: PRODUCT_BLOCK_TITLE,
    isChip: false,
    chips: [],
    isAnalysis: false,
    factors: [],
    features: [],
    content: '',
    hasContent: false,
    hasDescriptions: false,
    descriptions: [],
    media: null,
    hasOwnMedia: false,
    pills: [],
    activePill: '',
    subBlocks: [],
    isProducts: true,
    products
  };
  const index = blocks.map(item => item.title).indexOf(PRODUCT_BLOCK_ANCHOR);
  if (index < 0) return [block].concat(blocks);
  return blocks.slice(0, index + 1).concat([block], blocks.slice(index + 1));
}

module.exports = {
  CHIP_TITLES,
  MAX_PRODUCT_CONSTITUTIONS,
  MAX_PRODUCT_TAGS,
  PRODUCT_BLOCK_ANCHOR,
  PRODUCT_BLOCK_TITLE,
  SEASONS,
  TAB_DEFS,
  WEEKDAYS,
  blockHasRenderableContent,
  buildTabs,
  buildTree,
  collapseDescriptions,
  filterRenderableBlocks,
  injectProducts,
  normalizeGroups,
  normalizeNode,
  pickPills,
  profileSummaryOf,
  toBlock,
  toDescriptions,
  productReason,
  tabKeyOfTitle,
  toPlainText,
  toProductCards,
  toSubBlock,
  yuanLabel
};
