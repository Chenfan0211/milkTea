import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const detailDir = path.join(root, 'packageMall', 'pages', 'product-detail');
const read = name => fs.readFileSync(path.join(detailDir, name), 'utf8');
const wxml = read('product-detail.wxml');
const wxss = read('product-detail.wxss');
const js = read('product-detail.js');

// ----------------------------------------------------------------
// 一、主图白卡内轮播
// ----------------------------------------------------------------
assert.match(wxml, /class="mall-card detail-gallery"/, '主图必须使用白卡容器 detail-gallery');
assert.match(wxml, /<swiper[\s\S]*?class="detail-gallery__swiper"/, '主图必须是 swiper 结构');
assert.match(wxml, /<swiper-item wx:for="\{\{galleryImages\}\}"/, '轮播必须遍历 galleryImages 支持多图');
assert.match(wxml, /bindchange="onGalleryChange"/, '轮播必须绑定 onGalleryChange');
assert.match(wxml, /current="\{\{galleryIndex\}\}"/, '轮播必须受 galleryIndex 受控');

assert.match(js, /galleryIndex:\s*0/, 'data 必须初始化 galleryIndex');
assert.match(js, /onGalleryChange\(/, '必须实现 onGalleryChange');
assert.match(js, /event\.detail\.current/, 'onGalleryChange 必须读取 event.detail.current');
assert.match(js, /setData\(\{\s*galleryIndex:\s*index\s*\}\)/, 'onGalleryChange 必须回写 galleryIndex');
assert.match(js, /galleryIndex:\s*0,/, '加载商品后必须重置 galleryIndex');

// 多图才显示 当前/总数；售罄标签保留在主图卡内。
assert.match(
  wxml,
  /wx:if="\{\{galleryImages\.length > 1\}\}"[^>]*class="detail-gallery__counter"/,
  '多图时必须显示当前/总数指示'
);
assert.match(wxml, /\{\{galleryIndex \+ 1\}\}\/\{\{galleryImages\.length\}\}/, '计数器必须为 当前/总数 格式');
assert.match(
  wxml,
  /<view class="mall-card detail-gallery">[\s\S]*?wx:if="\{\{product\.soldOut\}\}"[^>]*class="detail-sold-out"/,
  '售罄标签必须保留在主图卡内'
);

// ----------------------------------------------------------------
// 二、商品信息卡分层：价格 + 划线价 / 名称 + 副标题 / 分类 + 已售
// ----------------------------------------------------------------
assert.match(wxml, /class="mall-card detail-summary"/, '必须存在商品信息卡');
assert.match(wxml, /detail-summary__price[\s\S]*?\{\{priceText\}\}/, '信息卡必须展示当前价格');
assert.match(
  wxml,
  /wx:if="\{\{selectedSku\.originalPriceText\}\}"[^>]*class="detail-summary__original"/,
  '信息卡必须有划线价且为空时不展示'
);
assert.match(wxml, /class="detail-summary__name">\{\{product\.name\}\}/, '信息卡必须展示商品名');
assert.match(wxml, /wx:if="\{\{product\.subtitle\}\}"[^>]*class="detail-summary__subtitle"/, '信息卡必须展示副标题');
assert.match(wxml, /class="mall-tag"[^>]*>\{\{product\.categoryName/, '信息卡必须有分类标签');
assert.match(wxml, /已售 \{\{product\.salesCount/, '信息卡必须展示已售数量');

// ----------------------------------------------------------------
// 三、选择规格卡：标题固定、显示已选、SKU 选中/售罄态、数量控制
// ----------------------------------------------------------------
assert.match(wxml, /class="mall-card detail-sku"/, '必须存在选择规格卡');
assert.match(wxml, /<text class="mall-section-title">选择规格<\/text>/, '规格卡标题必须固定为「选择规格」');
assert.match(wxml, /已选：\{\{selectedSku\.specValue/, '规格卡必须显示已选规格');
assert.match(
  wxml,
  /class="detail-sku__item \{\{selectedSkuId === item\.id \? 'is-selected' : ''\}\}/,
  'SKU 选中态必须保留'
);
assert.match(wxml, /item\.soldOut \? 'is-sold-out' : ''/, 'SKU 售罄态必须保留');
assert.match(wxml, /bindtap="selectSku"/, 'SKU 必须可点击切换');

assert.match(wxml, /class="detail-quantity"/, '规格卡必须包含数量控制');
assert.match(wxml, /bindtap="decreaseQuantity"/, '数量控制必须有减号');
assert.match(wxml, /bindtap="increaseQuantity"/, '数量控制必须有加号');
assert.match(wxml, /\{\{quantity\}\}/, '数量控制必须展示当前数量');
assert.match(js, /increaseQuantity\(\)\s*\{/, '必须实现 increaseQuantity');
assert.match(js, /decreaseQuantity\(\)\s*\{/, '必须实现 decreaseQuantity');
assert.match(js, /Math\.min\(99, sku\.availableStock\)/, '数量上限必须仍受可购买库存约束');
assert.match(js, /this\.data\.quantity <= 1/, '数量下限必须仍为 1');

// ----------------------------------------------------------------
// 四、商品说明卡四段
// ----------------------------------------------------------------
assert.match(wxml, /class="mall-card detail-description"/, '必须存在商品说明卡');
const descriptionBlock = wxml.slice(wxml.indexOf('detail-description'), wxml.indexOf('detail-note'));
for (const [label, expectation] of [
  ['简介', /\{\{product\.description/],
  ['规格', /\{\{selectedSku\.specValue/],
  ['配送', /全场包邮，发货后可查看物流信息/],
  ['售后', /发货后可按商城售后规则申请售后/]
]) {
  assert.match(
    descriptionBlock,
    new RegExp(`detail-description__label">${label}</text>`),
    `说明卡必须包含「${label}」段`
  );
  assert.match(descriptionBlock, expectation, `「${label}」段内容不符合约定`);
}
assert.equal((descriptionBlock.match(/detail-description__section/g) || []).length, 4, '说明卡必须恰好四段');

// ----------------------------------------------------------------
// 五、底部吸底操作栏不变
// ----------------------------------------------------------------
assert.match(wxml, /class="mall-action-bar"/, '底部操作栏必须存在');
assert.match(wxml, /bindtap="openCart"[\s\S]*?detail-cart-entry/, '底部必须保留购物车入口');
assert.match(wxml, /bindtap="addToCart"/, '底部必须保留加入购物车');
assert.match(wxml, /bindtap="buyNow"/, '底部必须保留立即购买');
assert.match(wxss, /@import\s+['"]\.\.\/\.\.\/styles\/mall\.wxss['"]/, '必须引入 mall.wxss 以复用吸底操作栏样式');

// ----------------------------------------------------------------
// 六、购买链路未被改动
// ----------------------------------------------------------------
assert.match(js, /mall\s*\.\s*validateItems\(/, '加入购物车/立即购买必须仍走服务端库存校验');
assert.match(js, /quoteMallCheckout|checkout\?/, '立即购买必须仍进入结算页');
assert.doesNotMatch(js, /fetch\(|XMLHttpRequest/, '不得绕过统一请求封装');

// ----------------------------------------------------------------
// 七、样式合规：rpx、设计 token、无硬编码颜色
// ----------------------------------------------------------------
assert.doesNotMatch(wxss, /\b\d+(?:\.\d+)?px\b/, 'product-detail.wxss 必须使用 rpx');
assert.doesNotMatch(wxss, /#[0-9A-Fa-f]{3,8}\b/, 'product-detail.wxss 不得写死颜色字面量');
assert.match(wxss, /var\(--radius-lg\)/, '白卡必须使用设计 token 圆角');
assert.match(wxss, /var\(--shadow-card\)/, '白卡必须使用设计 token 阴影');
assert.match(wxss, /\.detail-gallery__swiper\s*\{[\s\S]*?height:\s*\d+rpx/, 'swiper 必须显式声明高度，否则不渲染');

const allowedSpacing = new Set(['0', '1', '4', '8', '12', '16', '20', '24', '32', '40', '52', '64', '80', '104']);
for (const line of wxss.split('\n')) {
  if (!/^\s*(?:padding(?:-[a-z]+)?|margin(?:-[a-z]+)?|top|right|bottom|left|gap)\s*:/.test(line)) continue;
  for (const match of line.matchAll(/(?<![\d.])(\d+)rpx/g)) {
    assert.ok(allowedSpacing.has(match[1]), `间距必须使用设计档位：${line.trim()}`);
  }
}

// ----------------------------------------------------------------
// 八、无障碍：可点击节点必须有 aria-role / aria-label
// ----------------------------------------------------------------
for (const match of wxml.matchAll(/<view\b[^>]*bindtap="[^"]+"[^>]*>/g)) {
  assert.match(match[0], /aria-role="button"/, `可点击 view 缺少 aria-role: ${match[0]}`);
  assert.match(match[0], /aria-label=/, `可点击 view 缺少 aria-label: ${match[0]}`);
}

console.log('商城商品详情页白卡升级测试通过');
