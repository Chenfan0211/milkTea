import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const model = require(path.join(root, 'utils/product-detail-model.js'));

// ---- 1) 渲染模型：金额换算、纯文本清洗、规格与提示 ----
const view = model.buildProductDetailViewModel({
  id: 'tea-1',
  name: '滇绿',
  price: 1290,
  originalPrice: 1590,
  storedValuePrice: 200,
  image: 'list.jpg',
  galleryImage: 'gallery.jpg',
  imageDisclaimer: '图片仅供参考',
  priceLabel: '会员价',
  promotionText: '限时立减',
  description: '<p>清热利湿&nbsp;除烦</p>',
  ingredients: '绿茶',
  allergens: '无',
  cupCapacity: '500ml',
  tips: ['趁热饮用', '', '<b>少糖更清爽</b>'],
  tags: ['年度热销', ''],
  specGroups: [
    {
      id: 'size',
      label: '杯型',
      options: [
        { id: 'large', label: '大杯', priceDelta: 300 },
        { id: 'small', label: '小杯', priceDelta: 0 }
      ]
    }
  ]
});
assert.equal(view.id, 'tea-1');
assert.equal(view.name, '滇绿');
assert.equal(view.priceText, '¥12.9');
assert.equal(view.originalPriceText, '¥15.9', '原价高于现价时必须展示划线价');
assert.equal(view.image, 'gallery.jpg', '详情主图优先于列表图');
assert.equal(view.description, '清热利湿 除烦', '描述必须转纯文本并解实体');
assert.deepEqual(view.tips, ['趁热饮用', '少糖更清爽'], '提示必须清洗并过滤空值');
assert.deepEqual(view.tags, ['年度热销']);
assert.equal(view.specGroups[0].options[0].priceDeltaText, '¥3');
assert.equal(view.specGroups[0].options[1].priceDeltaText, '', '无加价不展示金额');

const noOriginal = model.buildProductDetailViewModel({ id: 'x', name: '同价商品', price: 990, originalPrice: 990 });
assert.equal(noOriginal.originalPriceText, '', '同价时不得展示划线价');

// ---- 2) 页面结构：四态、只读详情字段与无障碍重试 ----
const dir = path.join(root, 'pages/product-detail/product-detail');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(dir + '.' + extension), '缺少商品详情页文件: product-detail.' + extension);
}
const pageJs = fs.readFileSync(dir + '.js', 'utf8');
const pageWxml = fs.readFileSync(dir + '.wxml', 'utf8');
const pageWxss = fs.readFileSync(dir + '.wxss', 'utf8');
assert.ok(pageJs.includes('withShare('), '主包页面必须使用 withShare 包装');
assert.match(pageJs, /api\s*\.fetchProductDetail/, '详情页必须复用商品详情接口');
assert.ok(pageJs.includes('reportId'), '详情页必须读取报告上下文并用于推荐购物车隔离');
assert.ok(
  pageJs.includes('missing: true') && pageJs.includes('error:') && pageJs.includes('loading: true'),
  '详情页必须包含缺少编号/加载中/失败三态'
);
for (const binding of [
  'product.image',
  'product.name',
  'product.originalPriceText',
  'product.description',
  'product.ingredients',
  'product.allergens',
  'product.cupCapacity',
  'product.tips'
]) {
  assert.ok(pageWxml.includes(binding), '详情页必须展示 ' + binding);
}
assert.ok(
  pageWxml.includes('bindtap="load"') && pageWxml.includes('aria-label="重新加载商品详情"'),
  '失败态必须提供无障碍重试入口'
);
assert.ok(
  pageWxml.includes('selection.specGroups') && pageWxml.includes('bindtap="selectSpecOption"'),
  '规格信息必须直接显示在商品详情页'
);
assert.ok(
  !pageWxml.includes('<cart-sheet') && !pageWxml.includes('<cart-bar') && !pageWxml.includes('<spec-sheet'),
  '详情页必须只保留内嵌规格，不展示悬浮购物车或规格弹层'
);
assert.ok(
  pageWxml.includes('handleAddCart') && pageWxml.includes('handleBuyNow'),
  '详情页底部必须提供加入购物车和立即购买入口'
);
assert.ok(pageWxml.includes('increaseQuantity') && pageWxml.includes('decreaseQuantity'), '规格卡必须提供购买数量控制');
assert.ok(
  (pageWxml.match(/¥\{\{selection\.memberPrice\}\}/g) || []).length >= 2,
  '商品主卡与底部购买栏必须绑定同一实时价格'
);
const contentRule = (pageWxss.match(/\.product-detail-content\s*\{([\s\S]*?)\}/) || [])[1] || '';
assert.ok(
  contentRule.includes('var(--tabbar-height)') &&
    contentRule.includes('env(safe-area-inset-bottom)') &&
    contentRule.includes('40rpx'),
  '详情滚动内容必须预留底部操作栏、安全区与末卡间距'
);
assert.ok(!/\b\d+px\b/.test(pageWxss), '商品详情页不得使用 px');
const colorLiterals = [...pageWxss.matchAll(/#[0-9A-Fa-f]{3,8}\b/g)].filter(
  match => match[0].toUpperCase() !== '#FFFFFF'
);
assert.deepEqual(colorLiterals, [], '商品详情页不得写死品牌色值');

// ---- 3) 路由与分享参数 ----
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(appJson.pages.includes('pages/product-detail/product-detail'), '商品详情页必须注册到主包');
const share = require(path.join(root, 'utils/share.js'));
assert.equal(share.PAGE_SHARE_TITLES['pages/product-detail/product-detail'], '五零时光商品详情');
assert.deepEqual(share.SAFE_SHARE_PARAMS['pages/product-detail/product-detail'], ['productId']);

console.log('product-detail-page.test.mjs: passed');
