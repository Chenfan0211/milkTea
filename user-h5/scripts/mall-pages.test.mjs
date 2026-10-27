import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const mall = require(path.join(root, 'packageMall', 'utils', 'mall.js'));
const mallRoot = path.join(root, 'packageMall', 'pages');
const pageNames = [
  'home',
  'product-detail',
  'cart',
  'checkout',
  'address',
  'address-edit',
  'orders',
  'order-detail',
  'after-sale',
  'after-sales',
  'after-sale-detail'
];
const extensions = ['js', 'json', 'wxml', 'wxss'];

function readPage(name, extension) {
  return fs.readFileSync(path.join(mallRoot, name, `${name}.${extension}`), 'utf8');
}

const VOID_TAGS = new Set(['image', 'input', 'import', 'include', 'wxs', 'icon', 'progress', 'switch']);

function checkWxml(file) {
  const source = fs.readFileSync(file, 'utf8');
  const cleaned = source.replace(/<!--[\s\S]*?-->/g, '');
  const tagPattern = /<\/?([a-zA-Z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*?)(\/?)>/g;
  const stack = [];
  const errors = [];
  let match;
  while ((match = tagPattern.exec(cleaned)) !== null) {
    const raw = match[0];
    const tag = match[1];
    const selfClosing = match[3] === '/';
    if (raw.startsWith('</')) {
      const top = stack.pop();
      if (!top) errors.push(`多余结束标签 </${tag}>`);
      else if (top.tag !== tag) errors.push(`结束标签不匹配，期望 </${top.tag}>，实际 </${tag}>`);
      continue;
    }
    if (selfClosing || VOID_TAGS.has(tag)) continue;
    stack.push({ tag, line: cleaned.slice(0, match.index).split('\n').length });
  }
  for (const left of stack) errors.push(`缺少结束标签 </${left.tag}>（第 ${left.line} 行）`);
  return errors.map(error => `${path.basename(file)}: ${error}`);
}

// ---- 1) 分包子页面、路由与导航 ----
for (const name of pageNames) {
  for (const extension of extensions) {
    const file = path.join(mallRoot, name, `${name}.${extension}`);
    assert.ok(fs.existsSync(file), `缺少商城页面文件：${name}.${extension}`);
  }
  const config = JSON.parse(readPage(name, 'json'));
  assert.equal(config.navigationStyle, 'custom', `${name} 必须使用自定义导航栏`);
  assert.ok(config.usingComponents && config.usingComponents['navigation-bar'], `${name} 必须复用 navigation-bar 组件`);
}

const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
const mallSubpackage = (appJson.subpackages || []).find(item => item.root === 'packageMall');
assert.ok(mallSubpackage, 'app.json 必须注册 packageMall 分包');
assert.deepEqual(
  mallSubpackage.pages,
  pageNames.map(name => `pages/${name}/${name}`),
  'packageMall 分包页面必须与实现目录一致'
);

const wxmlErrors = pageNames.flatMap(name => checkWxml(path.join(mallRoot, name, `${name}.wxml`)));
assert.deepEqual(wxmlErrors, [], `商城 WXML 标签必须成对闭合：\n${wxmlErrors.join('\n')}`);

// ---- 2) 页面模块可加载且关键交互方法完整 ----
const expectedMethods = {
  home: ['loadCategories', 'loadProducts', 'selectCategory', 'loadMore', 'openProduct'],
  'product-detail': ['loadProduct', 'selectSku', 'addToCart', 'buyNow'],
  cart: ['loadCart', 'toggleSelected', 'increaseQuantity', 'decreaseQuantity', 'removeItem', 'checkout'],
  checkout: ['initialize', 'openAddressPicker', 'selectPayment', 'submitOrder', 'createAndPay'],
  address: ['loadAddresses', 'selectAddress', 'addAddress', 'editAddress', 'setDefault', 'removeAddress'],
  'address-edit': ['loadAddress', 'onRegionChange', 'saveAddress'],
  orders: ['loadOrders', 'selectStatus', 'continuePayment', 'cancelOrder', 'confirmReceipt'],
  'order-detail': ['loadOrder', 'continuePayment', 'cancelOrder', 'confirmReceipt', 'copyTrackingNo', 'openAfterSale'],
  'after-sale': ['loadOrder', 'toggleItem', 'changeQuantity', 'submitAfterSale'],
  'after-sales': ['loadAfterSales', 'openDetail'],
  'after-sale-detail': ['loadAfterSale']
};

let capturedPage = null;
global.Page = definition => {
  capturedPage = definition;
};
try {
  for (const name of pageNames) {
    capturedPage = null;
    require(path.join(mallRoot, name, `${name}.js`));
    assert.ok(capturedPage, `${name}.js 必须调用 Page()`);
    for (const method of expectedMethods[name]) {
      assert.equal(typeof capturedPage[method], 'function', `${name}.js 必须实现 ${method}()`);
    }
  }
} finally {
  delete global.Page;
}

// ---- 3) 商城渲染模型：售罄、地址快照、状态与倒计时 ----
assert.equal(
  mall.normalizeProduct({ id: 1, name: '售罄商品', availableStock: 0, soldOut: true }).soldOut,
  true,
  '商城商品必须按可用库存识别售罄'
);
assert.equal(
  mall.normalizeAddress({
    id: 1,
    provinceName: '湖南省',
    cityName: '长沙市',
    districtName: '岳麓区',
    detailAddress: 'A 栋'
  }).fullAddress,
  '湖南省 长沙市 岳麓区 A 栋',
  '地址快照必须拼接完整收货地址'
);
const shippedOrder = mall.decorateOrder({
  orderNo: 'M1',
  category: 'mall',
  orderType: 'MALL',
  status: 'SHIPPED',
  payStatus: 'PAID',
  paidAmount: 1290,
  autoCompleteTime: '2099-01-01 00:00:00',
  items: [{ id: 1, name: '茶礼', unitPrice: 1290, quantity: 1 }]
});
assert.equal(shippedOrder.statusText, '待收货', '商城已发货状态文案必须正确');
assert.equal(shippedOrder.canConfirm, true, '待收货订单必须允许确认收货');
assert.equal(shippedOrder.amountText, '12.9', '订单金额必须按分转元展示');
assert.match(shippedOrder.autoCompleteCountdown, /天/, '待收货订单必须展示自动完成倒计时');
const refundingOrder = mall.decorateOrder({
  status: 'PAID',
  payStatus: 'PAID',
  paidAmount: 100,
  refundStatus: 'PENDING'
});
assert.equal(refundingOrder.canCancel, false, '退款处理中的待发货订单不得重复取消');
assert.equal(refundingOrder.refundText, '退款处理中', '退款中状态必须明确展示');

// ---- 4) 商品列表、详情和单维 SKU ----
const homeJs = readPage('home', 'js');
const homeWxml = readPage('home', 'wxml');
assert.match(homeJs, /fetchMallCategories/, '商城首页必须加载分类');
assert.match(homeJs, /fetchMallProducts/, '商城首页必须分页加载商品');
assert.match(homeJs, /categoryId/, '商城首页必须支持分类筛选');
assert.match(homeWxml, /已售罄/, '售罄商品必须展示已售罄标签');
assert.match(homeWxml, /商品分类/, '商城首页必须有分类区域');

const detailJs = readPage('product-detail', 'js');
const detailWxml = readPage('product-detail', 'wxml');
assert.match(detailJs, /fetchMallProductDetail/, '详情页必须调用商品详情接口');
assert.match(detailJs, /selectedSku/, '详情页必须维护单维 SKU 选中态');
assert.match(detailJs, /availableStock/, '详情页必须按 SKU 可用库存限制购买');
assert.match(detailJs, /mallCart\.add/, '详情页加入购物车必须复用 mall-cart');
assert.match(detailWxml, /bindtap="selectSku"/, '详情页必须提供 SKU 选择');
assert.match(detailWxml, /addToCart/, '详情页必须提供加入购物车入口');
assert.match(detailWxml, /buyNow/, '详情页必须提供立即购买入口');

// ---- 5) 购物车本地校验、勾选、数量与删除 ----
const cartJs = readPage('cart', 'js');
const cartWxml = readPage('cart', 'wxml');
assert.match(cartJs, /validateMallSkus/, '购物车必须按实际数量调用 SKU 校验接口');
assert.match(
  fs.readFileSync(path.join(root, 'utils', 'api.js'), 'utf8'),
  /quoteMallCheckout/,
  '结算金额必须走服务端结算确认接口'
);
assert.match(cartJs, /mallCart\.read|mallCart\.update|mallCart\.remove/, '购物车必须复用 mall-cart 状态');
assert.match(cartJs, /toggleSelected/, '购物车必须支持勾选');
assert.match(cartJs, /increaseQuantity/, '购物车必须支持增加数量');
assert.match(cartJs, /decreaseQuantity/, '购物车必须支持减少数量');
assert.match(cartJs, /removeItem/, '购物车必须支持删除商品');
assert.match(cartWxml, /已失效/, '购物车必须展示失效商品状态');
assert.match(cartWxml, /amountText/, '购物车必须展示选中商品合计');

// ---- 6) 地址 CRUD、地区 picker 与确认订单 ----
const addressJs = readPage('address', 'js');
const addressWxml = readPage('address', 'wxml');
assert.match(addressJs, /fetchAddresses/, '地址列表必须从地址接口读取');
assert.match(addressJs, /deleteAddress/, '地址列表必须支持删除');
assert.match(addressJs, /setDefaultAddress/, '地址列表必须支持设默认');
assert.match(addressJs, /selectAddress/, '地址列表必须支持结算选择模式');
assert.match(addressWxml, /新增收货地址/, '地址列表必须提供新增地址入口');

const addressEditJs = readPage('address-edit', 'js');
const addressEditWxml = readPage('address-edit', 'wxml');
assert.match(addressEditJs, /createAddress/, '地址编辑页必须支持新增地址');
assert.match(addressEditJs, /updateAddress/, '地址编辑页必须支持编辑地址');
assert.match(addressEditWxml, /mode="region"/, '地址编辑必须复用原生省市县 picker');
assert.match(addressEditWxml, /bindchange="onRegionChange"/, '地区 picker 必须回填省市县');
assert.match(addressEditWxml, /保存地址/, '地址编辑页必须提供保存入口');

const checkoutJs = readPage('checkout', 'js');
const checkoutWxml = readPage('checkout', 'wxml');
assert.match(checkoutJs, /fetchAddresses/, '确认订单页必须读取收货地址');
assert.match(checkoutJs, /createMallOrder/, '确认订单页必须创建商城订单');
assert.match(checkoutJs, /payOrderByBalance/, '确认订单页必须支持储值余额支付');
assert.match(checkoutJs, /requestOrderPayment/, '确认订单页必须支持微信支付及状态轮询');
assert.match(checkoutJs, /requestMallShippingSubscribe/, '确认订单页必须调用商城发货订阅消息 API');
assert.match(checkoutWxml, /STORED_VALUE/, '确认订单页必须提供余额支付选项');
assert.match(checkoutWxml, /WXPAY/, '确认订单页必须提供微信支付选项');

// ---- 7) 商城订单筛选、操作、物流与 15 天倒计时 ----
const ordersJs = readPage('orders', 'js');
const ordersWxml = readPage('orders', 'wxml');
assert.match(ordersJs, /category:\s*'mall'/, '订单列表必须限定商城分类');
assert.match(ordersJs, /options\.status\s*=\s*status|statusFilter/, '订单列表必须支持状态筛选');
assert.match(ordersJs, /待发货/, '订单列表必须提供待发货筛选');
assert.match(ordersJs, /待收货/, '订单列表必须提供待收货筛选');
assert.match(ordersJs, /cancelOrder/, '订单列表必须支持待支付/待发货取消');
assert.match(ordersJs, /confirmMallReceipt/, '订单列表必须支持确认收货');
assert.match(ordersJs, /requestOrderPayment/, '订单列表必须支持待支付继续支付');
assert.match(ordersJs, /autoCompleteTime/, '订单列表必须展示 15 天自动完成倒计时');
assert.match(ordersWxml, /确认收货/, '订单列表必须提供确认收货操作');

const orderDetailJs = readPage('order-detail', 'js');
const orderDetailWxml = readPage('order-detail', 'wxml');
assert.match(orderDetailJs, /fetchOrderDetail/, '订单详情必须调用订单详情接口');
assert.match(orderDetailJs, /shipping/, '订单详情必须处理物流与地址快照');
assert.match(orderDetailJs, /autoCompleteTime/, '订单详情必须计算自动完成倒计时');
assert.match(orderDetailWxml, /物流信息/, '订单详情必须展示物流信息');
assert.match(orderDetailWxml, /倒计时/, '订单详情必须展示自动完成倒计时');
assert.match(orderDetailWxml, /确认收货/, '订单详情必须提供确认收货操作');
assert.match(orderDetailWxml, /申请售后/, '已发货/已完成订单详情必须提供售后入口');
const afterSaleJs = readPage('after-sale', 'js');
const afterSaleWxml = readPage('after-sale', 'wxml');
assert.match(afterSaleJs, /createAfterSale/, '售后申请页必须调用创建售后接口');
assert.match(afterSaleJs, /refundedQuantity/, '售后申请页必须扣除历史已退数量');
assert.match(orderDetailWxml, /售后处理中/, '有处理中售后时订单详情必须展示售后状态');
assert.match(orderDetailWxml, /openAfterSales/, '订单详情必须提供售后记录入口');
assert.match(afterSaleJs, /refundAmount/, '售后申请页必须展示服务端口径金额');
assert.match(afterSaleJs, /activeAfterSale/, '售后申请页必须拦截已有处理中售后');
assert.ok(!/amount\\s*:/.test(afterSaleJs), '售后申请页不得向客户端提交退款金额');
assert.match(afterSaleWxml, /申请售后/, '售后申请页必须展示明确标题');

// ---- 8) 设计 token、Lucide、无障碍与禁用项 ----
const sharedWxss = fs.readFileSync(path.join(root, 'packageMall', 'styles', 'mall.wxss'), 'utf8');
const allowedSpacing = new Set(['0', '4', '8', '12', '16', '20', '24', '32', '40']);
const availableIcons = new Set(
  fs
    .readdirSync(path.join(root, 'assets', 'icons', 'lucide'))
    .filter(file => file.endsWith('.svg'))
    .map(file => file.slice(0, -4))
);

for (const [name, wxss] of [['shared', sharedWxss], ...pageNames.map(name => [name, readPage(name, 'wxss')])]) {
  assert.ok(!/\b\d+(?:\.\d+)?px\b/.test(wxss), `${name} 样式必须使用 rpx，不得出现 px`);
  const colorLiterals = [...wxss.matchAll(/#[0-9A-Fa-f]{3,8}\b/g)]
    .map(match => match[0].toUpperCase())
    .filter(color => color !== '#FFFFFF');
  assert.deepEqual(colorLiterals, [], `${name} 不得写死设计 token 以外的颜色`);
  for (const line of wxss.split('\n')) {
    if (!/^\s*(?:padding(?:-[a-z]+)?|margin(?:-[a-z]+)?|top|right|bottom|left|gap)\s*:/.test(line)) continue;
    for (const match of line.matchAll(/(?<![\d.])(\d+)rpx/g)) {
      assert.ok(allowedSpacing.has(match[1]), `${name} 间距必须使用设计档位：${line.trim()}`);
    }
  }
}

for (const name of pageNames) {
  const wxml = readPage(name, 'wxml');
  const js = readPage(name, 'js');
  assert.ok(!/<button[\s>]/i.test(wxml), `${name} 禁止使用原生 button`);
  assert.ok(!/<(?:van|t)-[\w-]+/i.test(wxml), `${name} 禁止引入第三方 UI 组件`);
  assert.ok(!/<svg[\s>]/i.test(wxml), `${name} 禁止手写 SVG`);
  assert.ok(!/https?:\/\//.test(wxml + js), `${name} 禁止远程 CDN 资源`);
  assert.ok(!/assets\/temp\//.test(wxml + js), `${name} 禁止引用临时素材`);
  assert.match(wxml, /aria-role="button"/, `${name} 可点击元素必须声明 aria-role`);
  assert.match(wxml, /aria-label=/, `${name} 可点击元素必须提供 aria-label`);

  for (const match of wxml.matchAll(/<(view|image|text)\b[^>]*(?:bindtap|catchtap)="[^"]*"[^>]*>/g)) {
    assert.match(match[0], /aria-role="button"/, `${name} 可点击节点必须声明 aria-role=button`);
    assert.match(match[0], /aria-label=/, `${name} 可点击节点必须提供 aria-label`);
  }

  const iconSources = [...wxml.matchAll(/src="([^"]+\.svg)"/g)].map(match => match[1]);
  for (const icon of iconSources) {
    assert.ok(icon.startsWith('/assets/icons/lucide/'), `${name} 图标必须来自本地 Lucide：${icon}`);
    assert.ok(
      availableIcons.has(icon.slice('/assets/icons/lucide/'.length, -4)),
      `${name} 引用了不存在的 Lucide 图标：${icon}`
    );
  }
}

console.log(`商城分包子页面结构测试通过（${pageNames.length} 个页面）`);
