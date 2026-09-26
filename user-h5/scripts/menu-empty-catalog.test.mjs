import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 点单页空菜单健壮性回归测试。
 *
 * 线上崩溃现场（2026-09-25）：
 *   TypeError: Cannot read properties of undefined (reading 'categories')
 *     at getFirstCategoryId (pages/menu/menu.js:17)
 *     at syncCurrentStore  -> buildListingUpdates
 *
 * 根因：菜单改由 /api/v1/app/menu 下发后，getMergedMenuTab() 在
 * 「接口未返回 / 该门店可售商品为 0」时返回 null，而调用方直接读
 * activeMenu.groups[0].categories[0].id，缺少判空。
 *
 * 本测试锁死三处契约，防止回归：
 *   1) getMergedMenuTab 恒返回带 groups 数组的对象；
 *   2) 点单页在空菜单下不抛异常，且把 activeMenu/选中态/商品行清空；
 *   3) 菜单就绪后仍能正常选中首个分类。
 */

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

const storage = new Map();
let capturedPage = null;

globalThis.wx = {
  getStorageSync(key) {
    return storage.has(key) ? storage.get(key) : '';
  },
  setStorageSync(key, value) {
    storage.set(key, value);
  },
  removeStorageSync(key) {
    storage.delete(key);
  },
  showToast() {},
  makePhoneCall() {},
  openLocation() {},
  switchTab() {},
  navigateTo() {},
  navigateBack() {},
  request() {
    // 本测试不依赖真实接口：菜单镜像由 setMenuCatalogForTest 直接注入
  }
};

globalThis.getApp = () => ({
  globalData: {
    orderMode: 'pickup',
    selectedStoreId: null,
    selectedCityCode: 'changsha',
    selectedCityName: '长沙市'
  }
});
globalThis.Page = config => {
  capturedPage = config;
};

const { setMenuCatalogForTest, getMergedMenuTab } = require(path.join(root, 'utils/product-listing.js'));

// ---------- 1. getMergedMenuTab 契约：恒返回 groups 数组 ----------
setMenuCatalogForTest([]);
const emptyMenu = getMergedMenuTab('store-001');
assert.ok(emptyMenu && Array.isArray(emptyMenu.groups), '菜单镜像为空时必须返回带 groups 数组的对象（不得返回 null）');
assert.equal(emptyMenu.groups.length, 0, '空菜单的 groups 必须为空数组');

setMenuCatalogForTest([
  { id: 'menu', label: '菜单', groups: [{ id: 'all', label: '全部', categories: [{ id: 'herbal', label: '草本养生茶', products: [] }] }] }
]);
const readyMenu = getMergedMenuTab('store-001');
assert.equal(readyMenu.groups[0].categories[0].id, 'herbal', '菜单就绪时必须能取到首个分类');

// ---------- 2. 点单页在空菜单下不得抛出异常 ----------
require(path.join(root, 'pages/menu/menu.js'));
assert.ok(capturedPage, '必须捕获到点单页配置');

function createPage(config) {
  const instance = Object.assign({}, config);
  instance.data = JSON.parse(JSON.stringify(config.data));
  instance.setData = updates => Object.assign(instance.data, updates);
  instance.tabBarState = { selected: 0, hidden: false };
  instance.getTabBar = () => ({ setData: updates => Object.assign(instance.tabBarState, updates) });
  return instance;
}

const page = createPage(capturedPage);
// 预置脏状态：验证空菜单时这些字段会被清空，而不是残留旧值
page.setData({
  activeMenu: readyMenu,
  selectedCategoryId: 'herbal',
  selectedGroupId: 'all',
  scrollIntoView: 'anchor-herbal',
  productRows: [{ type: 'category', id: 'anchor-herbal', label: '草本养生茶' }]
});

setMenuCatalogForTest([]);
assert.doesNotThrow(
  () => page.syncCurrentStore(),
  '菜单为空时 syncCurrentStore 不得抛异常（原崩溃点）'
);
assert.deepEqual(page.data.activeMenu, { groups: [] }, '空菜单时 activeMenu 必须回落为空骨架');
assert.equal(page.data.selectedCategoryId, '', '空菜单时必须清空选中分类');
assert.equal(page.data.selectedGroupId, '', '空菜单时必须清空选中分组');
assert.equal(page.data.scrollIntoView, '', '空菜单时必须清空滚动锚点');

// renderMenu 是首屏另一条入口，同样不得抛异常且必须清空
assert.doesNotThrow(() => page.renderMenu(), '菜单为空时 renderMenu 不得抛异常');
assert.deepEqual(page.data.activeMenu, { groups: [] }, 'renderMenu 空菜单时 activeMenu 必须回落为空骨架');
assert.deepEqual(page.data.productRows, [], '空菜单时必须清空商品行，不得残留上一次的数据');

// ---------- 3. 菜单就绪后恢复正常渲染 ----------
setMenuCatalogForTest([
  {
    id: 'menu',
    label: '菜单',
    groups: [
      {
        id: 'all',
        label: '全部',
        categories: [
          {
            id: 'herbal',
            label: '草本养生茶',
            products: [{ id: 'classic-001', name: '五窨茉莉抹茶', price: 1390, image: '/assets/images/3x/menu-product.jpg' }]
          }
        ]
      }
    ]
  }
]);
assert.doesNotThrow(() => page.renderMenu(), '菜单就绪时 renderMenu 不得抛异常');
assert.equal(page.data.selectedCategoryId, 'herbal', '菜单恢复后必须重新选中首个分类');
assert.equal(page.data.selectedGroupId, 'all', '菜单恢复后必须重新选中首个分组');
assert.ok(page.data.productRows.length > 0, '菜单恢复后必须重建商品行');

// ---------- 4. 分类点击不得读取越界分组 ----------
assert.doesNotThrow(
  () => page.selectCategory({ currentTarget: { dataset: { id: 'herbal' } } }),
  '点击分类不得因分组越界而抛异常'
);
page.setData({ activeMenu: { groups: [] } });
assert.doesNotThrow(
  () => page.selectCategory({ currentTarget: { dataset: { id: 'ghost' } } }),
  '菜单为空时点击分类必须安全降级，不得抛异常'
);

// ---------- 5. 分类标签（左上角角标）链路 ----------
// 背景：tag 由 V30 建列、V41 补示例数据；wxml 有 wx:if 判空渲染。
// 这里锁死「数据非空 -> 渲染；数据为空 -> 不渲染」，避免标签再次整块消失。
const menuWxml = fs.readFileSync(path.join(root, 'pages/menu/menu.wxml'), 'utf8');
const menuWxss = fs.readFileSync(path.join(root, 'pages/menu/menu.wxss'), 'utf8');
assert.ok(
  menuWxml.includes('wx:if="{{category.tag}}"') && menuWxml.includes('category-item__tag'),
  '分类左上角标签必须由 category.tag 数据驱动渲染'
);
assert.ok(
  /\.category-item__tag[\s\S]*?background:\s*var\(--brand-green\)/.test(menuWxss),
  '分类标签底色必须使用 design-system token（--brand-green），不得写死色值'
);
assert.ok(
  /\.category-item__tag[\s\S]*?border-radius:\s*0\s+8rpx\s+8rpx\s+0/.test(menuWxss),
  '分类标签必须左侧贴边、仅右侧圆角（参考图形状口径）'
);
// 接口链路：MenuDTO 必须下发 tag，且服务端从 product_category.tag 取值
const menuDto = fs.readFileSync(
  path.join(root, '..', 'product-service/src/main/java/com/wuling/product/dto/MenuDTO.java'),
  'utf8'
);
assert.ok(
  /private String tag;/.test(menuDto),
  '菜单接口 DTO 必须保留分类 tag 字段，否则小程序拿不到标签'
);
// 迁移必须真的写入 tag 数据：tag 字段建了但没种子数据，标签同样不会显示
const migrationDir = path.join(root, '..', 'server/src/main/resources/db/migration');
const tagSeed = fs
  .readdirSync(migrationDir)
  .filter(name => /^V\d+__.*\.sql$/.test(name))
  .map(name => fs.readFileSync(path.join(migrationDir, name), 'utf8'))
  .join('\n');
assert.ok(
  /UPDATE product_category[\s\S]*?SET tag\s*=/.test(tagSeed),
  '必须存在为 product_category.tag 写入数据的迁移，否则左上角标签永远不渲染'
);

// ---------- 6. onShow 统一入口：每次进入都必须拉接口（B 方案） ----------
// 历史 bug：旧 onShow 里「无缓存门店」那条分支只调 openStorePicker()，一个请求都不发，
// 但 renderMenu 已用「未按门店过滤」的兜底数据画了一版菜单 —— 用户看到「不走接口的菜单」。
const menuJs = fs.readFileSync(path.join(root, 'pages/menu/menu.js'), 'utf8');
// 直接取出 onShow 函数体断言，避免正则被注释/空行影响而漏判。
const onShowBody = menuJs.match(/onShow\(\)\s*\{([\s\S]*?)\n    \},/);
assert.ok(onShowBody, '必须能定位到 onShow 函数体');
assert.ok(
  !/openStorePicker\(/.test(onShowBody[1]),
  'onShow 不得保留「无门店时只开门店层、不发请求」的分支（门店层应由 catalog 状态自动决定）'
);
assert.ok(
  /this\.refreshThenSync\(\)/.test(onShowBody[1]),
  'onShow 必须统一走 refreshThenSync（先拉接口再渲染）'
);
assert.ok(
  /onTabItemTap\(\)\s*\{\s*this\.onShow\(\);/.test(menuJs),
  'onTabItemTap 必须复用 onShow，避免再次出现只开层不请求的岔路'
);
assert.ok(/onPullDownRefresh\(\)/.test(menuJs), '点单页必须实现下拉刷新');

// 下拉刷新必须在页面配置里开启，否则回调不会触发
const menuJson = JSON.parse(fs.readFileSync(path.join(root, 'pages/menu/menu.json'), 'utf8'));
assert.equal(menuJson.enablePullDownRefresh, true, 'menu.json 必须开启 enablePullDownRefresh');

// 切门店必须重新拉菜单，否则会继续展示上一家门店的商品与上下架状态
assert.ok(
  /handleSelectPickerStore\(event\)[\s\S]*?this\.refreshThenSync\(\)/.test(menuJs),
  '门店选择层选中门店后必须重新拉取该门店的菜单'
);

// ---------- 7. 菜单同步状态：区分「首次失败」与「刷新失败」 ----------
const { getMenuSyncState, refreshMenuFromRemote, getMenuCatalog } = require(path.join(root, 'utils/product-listing.js'));
assert.equal(typeof getMenuSyncState, 'function', 'product-listing 必须导出 getMenuSyncState');

// 首次失败：loaded=false + 有 error -> 页面据此提示用户可下拉重试
setMenuCatalogForTest([]);
globalThis.wx.request = opts => {
  setTimeout(() => opts.fail && opts.fail({ errMsg: 'request:fail' }), 0);
};
await refreshMenuFromRemote();
let sync = getMenuSyncState();
assert.ok(sync.error, '首次加载失败必须记录 error，否则页面无法提示用户');
assert.equal(sync.loaded, false, '首次加载失败时 loaded 必须为 false');

// 已有镜像时失败：loaded 保持 true -> 静默保留旧数据，不打扰用户
globalThis.wx.request = opts => {
  setTimeout(() => opts.success({ statusCode: 200, data: { code: 0, data: [
    { id: 'menu', label: '菜单', groups: [{ id: 'all', label: '全部', categories: [{ id: 'herbal', label: '草本养生茶', products: [] }] }] }
  ] } }), 0);
};
await refreshMenuFromRemote();
assert.equal(getMenuSyncState().loaded, true, '成功加载后 loaded 必须为 true');
globalThis.wx.request = opts => {
  setTimeout(() => opts.fail && opts.fail({ errMsg: 'request:fail' }), 0);
};
await refreshMenuFromRemote();
sync = getMenuSyncState();
assert.equal(sync.loaded, true, '已有镜像时刷新失败必须保持 loaded=true，避免无谓打扰用户');
assert.ok(sync.error, '刷新失败仍应记录 error 以便排查');
assert.ok(getMenuCatalog().length > 0, '刷新失败必须保留旧镜像，不得把页面打空');

// ---------- 8. 商品列表底部必须为自定义 TabBar 留白 ----------
// 历史 bug：product-scroll 只留了 40rpx + 安全区，而 custom-tab-bar 是 fixed 定位、
// 高 112rpx，导致滚到最后一个商品时卡片下半部分被 TabBar 盖住（信息被遮挡）。
{
  const scrollRule = menuWxss.match(/\.product-scroll\s*\{([\s\S]*?)\}/)?.[1] || '';
  assert.ok(
    /padding:[\s\S]*?var\(--tabbar-height\)/.test(scrollRule),
    '商品区底部必须用 --tabbar-height 预留自定义 TabBar 高度，否则最后一个商品会被遮挡'
  );
  assert.ok(
    /env\(safe-area-inset-bottom\)/.test(scrollRule),
    '商品区底部必须叠加 env(safe-area-inset-bottom)，适配全面屏'
  );

  // 留白必须与真实 TabBar / cart-bar 高度口径一致，不能写死近似值
  const tabbarWxss = fs.readFileSync(path.join(root, 'custom-tab-bar/index.wxss'), 'utf8');
  const appWxss = fs.readFileSync(path.join(root, 'app.wxss'), 'utf8');
  const tabbarVar = Number((appWxss.match(/--tabbar-height:\s*(\d+)rpx/) || [])[1]);
  const tabbarReal = Number((tabbarWxss.match(/height:\s*calc\((\d+)rpx/) || [])[1]);
  assert.ok(tabbarVar > 0 && tabbarReal > 0, '必须能解析出 --tabbar-height 与 custom-tab-bar 高度');
  assert.equal(
    tabbarVar,
    tabbarReal,
    `--tabbar-height(${tabbarVar}rpx) 必须与 custom-tab-bar 实际高度(${tabbarReal}rpx)一致，否则留白算错`
  );

  // 有购物车时还要额外让开 cart-bar（fixed，位于 TabBar 之上）
  const cartScrollRule = menuWxss.match(/\.product-scroll\.has-cart\s*\{([\s\S]*?)\}/)?.[1] || '';
  const cartBarWxss = fs.readFileSync(path.join(root, 'components/cart-bar/cart-bar.wxss'), 'utf8');
  assert.ok(
    /var\(--tabbar-height\)/.test(cartScrollRule),
    '有购物车时底部留白必须仍包含 TabBar 高度（cart-bar 叠在 TabBar 之上）'
  );
  assert.ok(
    /bottom:\s*calc\(var\(--tabbar-height\)/.test(cartBarWxss),
    'cart-bar 必须固定在 TabBar 之上，底部留白口径需与之一致'
  );
}

// ---------- 9. storePickerVisible 必须双向显式赋值 ----------
// 历史 bug：buildCatalogUpdates 只在 openPicker 为真时赋 true，为假时不赋值，
// 于是页面 data 里的初值 true 会一直残留 —— 表现为「已选好门店、商品列表已渲染，
// 但门店选择层仍处于展开状态」。该字段必须每次都被显式写成一个确定值。
{
  const helperBody = menuJs.match(/buildCatalogUpdates\(catalog, options = \{\}\)\s*\{([\s\S]*?)\n    \},/);
  assert.ok(helperBody, '必须能定位到 buildCatalogUpdates 函数体');
  assert.ok(
    /updates\.storePickerVisible\s*=\s*Boolean\(options\.openPicker\)/.test(helperBody[1]),
    'buildCatalogUpdates 必须双向显式赋值 storePickerVisible，不得只在 openPicker 为真时赋值'
  );
}

// ---------- 10. onLoad 不得与 onShow 并发渲染 ----------
// onLoad 自己渲染会和 onShow 的异步链抢 setData（谁后返回谁覆盖），
// 曾导致「门店已缓存，选择层却又被打开」的竞态。渲染必须只由 onShow 负责。
{
  const onLoadBody = menuJs.match(/onLoad\(options\)\s*\{([\s\S]*?)\n    \},/) ||
                     menuJs.match(/onLoad\(\)\s*\{([\s\S]*?)\n    \},/);
  assert.ok(onLoadBody, '必须能定位到 onLoad 函数体');
  // 先去注释再断言：函数体里的历史说明也提到了 renderMenu()，不能误判
  const onLoadCode = onLoadBody[1].replace(/\/\/[^\n]*/g, '');
  assert.ok(
    !/renderMenu\(\)/.test(onLoadCode),
    'onLoad 不得调用 renderMenu（会与 onShow 的 refreshThenSync 并发渲染，产生竞态）'
  );
}

// ---------- 11. 左侧分类栏 1:1 几何（对照参考图实测值） ----------
// 参考图 1170px 宽 = 750rpx，换算系数 0.641；数值均取整到 design-system 档位。
{
  const scrollRule = menuWxss.match(/\.category-scroll\s*\{([\s\S]*?)\}/)?.[1] || '';
  assert.ok(
    /width:\s*195rpx/.test(scrollRule),
    '分类栏宽度必须为 195rpx（参考图实测 304px 换算）'
  );

  const itemRule = menuWxss.match(/\.category-item\s*\{([\s\S]*?)\}/)?.[1] || '';
  assert.ok(
    /min-height:\s*88rpx/.test(itemRule),
    '分类项高度必须为 88rpx（参考图实测两项行距 137px 换算）'
  );
  assert.ok(
    /flex-direction:\s*column/.test(itemRule),
    '分类项必须竖向排列，复现「标签在上、分类名在下」的两行结构'
  );

  // 标签必须是「独立占位」而非 absolute 叠字，否则会与分类名重叠
  const tagRule = menuWxss.match(/\.category-item__tag\s*\{([\s\S]*?)\}/)?.[1] || '';
  assert.ok(
    !/position:\s*absolute/.test(tagRule),
    '分类标签必须占独立一行，不得使用 absolute 叠在分类名上'
  );
  assert.ok(
    /align-self:\s*flex-start/.test(tagRule),
    '分类标签必须左贴边（align-self: flex-start）'
  );
  assert.ok(
    /line-height:\s*29rpx/.test(tagRule),
    '分类标签高度必须为 29rpx（参考图实测 45px 换算）'
  );
  assert.ok(
    /margin-top:\s*20rpx/.test(tagRule),
    '分类标签距顶必须为 20rpx（参考图实测 21rpx 归到规范档位）'
  );
  assert.ok(
    /border-radius:\s*0\s+8rpx\s+8rpx\s+0/.test(tagRule),
    '分类标签必须左侧贴边、仅右侧圆角'
  );

  // 无标签的分类不得凭空多出间隙：间隙只能用相邻兄弟选择器给
  assert.ok(
    /\.category-item__tag\s*\+\s*\.category-item__label\s*\{[\s\S]*?margin-top/.test(menuWxss),
    '标签与分类名的间隙必须只用相邻兄弟选择器，避免无标签分类产生偏移'
  );
}

// ---------- 12. 本版暂不上线的两处入口必须保持隐藏 ----------
// 需求：活动优惠行 + 新品广告位本版不做，先隐藏。
// 用 wx:if="{{false}}" 保留代码结构（便于后续恢复），但要断言「确实被隐藏」，
// 否则会出现「测试全绿、元素却露在页面上」的假阳性
// （既有 activity-sheet 测试只做字符串包含判断，无法发现被隐藏或未隐藏）。
{
  const couponBlock = menuWxml.match(/<view[^>]*class="coupon-row"[\s\S]*?<\/view>\s*<\/view>/);
  assert.ok(couponBlock, '必须能定位到活动优惠行结构（隐藏也应保留代码）');
  assert.ok(
    /wx:if="\{\{false\}\}"/.test(couponBlock[0].split('\n')[0]),
    '活动优惠行必须用 wx:if="{{false}}" 隐藏'
  );

  const adTitleTag = menuWxml.match(/<text[^>]*class="ad-title"[^>]*>/);
  assert.ok(adTitleTag, '必须保留广告标题结构（本版隐藏，不删除）');
  assert.ok(
    /wx:if="\{\{false\}\}"/.test(adTitleTag[0]),
    '广告标题必须用 wx:if="{{false}}" 隐藏'
  );

  const adBannerTag = menuWxml.match(/<image[^>]*class="ad-banner"[^>]*>/);
  assert.ok(adBannerTag, '必须保留广告图结构（本版隐藏，不删除）');
  assert.ok(
    /wx:if="\{\{false\}\}"/.test(adBannerTag[0]),
    '广告图必须用 wx:if="{{false}}" 隐藏'
  );

  // 首屏滚动锚点不能跟着隐藏：scrollIntoView 初始值依赖它存在
  assert.ok(
    /<list-item id="product-top"/.test(menuWxml),
    '必须保留 id="product-top" 锚点，否则首屏 scrollIntoView 定位失效'
  );
  assert.ok(
    !/wx:if="\{\{false\}\}"[^>]*id="product-top"/.test(menuWxml),
    'id="product-top" 锚点自身不得被隐藏'
  );
}

// ---------- 13. 下单不得由前端提交价格或会员等级 ----------
// 安全口径（用户确认）：前端只传「商品信息 + 用户信息」，
// 会员等级与商品金额一律由后端查库计算。
// 若前端开始提交价格/等级，就等于把计价权交给了客户端 —— 可被改包篡改。
{
  const confirmJs = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
  const payloadMatch = confirmJs.match(/\.createOrder\(\{([\s\S]*?)\}\)/);
  assert.ok(payloadMatch, '必须能定位到下单请求体');
  const payload = payloadMatch[1];

  // clientAmount 允许携带（用户确认：前端传值做交叉校验），
  // 但它是「校验输入」而非「成交价」——后端一律自己算，见第 14 节断言。
  assert.ok(
    !/vipLevel/.test(payload),
    '下单请求体不得携带 vipLevel（等级由后端查库，前端传入可被改包提升）'
  );
  // 唯一允许的价格类字段是 clientAmount（校验用）；
  // 除此之外不得出现任何成交价字段（后端会自行重算，见第 14 节）。

  // 只应包含：门店、用餐方式、商品明细
  assert.ok(/storeSubjectId/.test(payload), '下单请求体必须包含门店');
  assert.ok(/mealType/.test(payload), '下单请求体必须包含用餐方式');
  assert.ok(/items/.test(payload), '下单请求体必须包含商品明细');
}

// ---------- 14. clientAmount 必须是「分」，且不得由前端决定成交价 ----------
{
  const confirmJs = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');

  assert.ok(/clientAmount/.test(confirmJs), '下单必须带 clientAmount 供后端交叉校验');

  // 单位：前端购物车价格是「元」（normalizeSpecProduct 已除 100），
  // 而后端 clientAmount 语义是「分」。漏乘 100 会让校验恒为 false，
  // 表现是「校验永远不通过」但金额仍正确 —— 静默失效，很难发现。
  // 注意：只断言「toFen 存在」是不够的 —— 定义了却没用（直接传元值）
  // 同样会让校验恒不通过。必须断言 clientAmount 的赋值表达式里真的调用了 toFen。
  const assignMatch = confirmJs.match(/const clientAmount\s*=\s*([\s\S]*?);/);
  assert.ok(assignMatch, '必须能定位 clientAmount 的赋值表达式');
  assert.ok(
    /toFen\(/.test(assignMatch[1]),
    'clientAmount 赋值必须调用 toFen() 转成「分」；直接传「元」会让后端校验恒不通过'
  );
  assert.ok(
    /function toFen\(/.test(confirmJs) && /Math\.round\(Number\(yuan \|\| 0\) \* 100\)/.test(confirmJs),
    'toFen 必须按「元 × 100」并四舍五入换算为分'
  );

  // 储值支付有额外立减，与后端「会员价」口径不同，传金额必然判为不一致
  assert.ok(
    /paymentMethod === 'stored-value' \? null/.test(confirmJs),
    '储值支付时不得传 clientAmount（口径与后端会员价不同，会恒判不一致）'
  );

  // clientAmount 只用于校验：不得据此决定提交金额
  assert.ok(
    !/paidAmount\s*:/.test(confirmJs) && !/totalAmount\s*:/.test(confirmJs),
    '前端不得提交成交金额，金额必须由后端计算'
  );
}

// ---------- 15. 确认页金额必须与全链路同口径（原价 × 等级折扣） ----------
// 历史 bug：summarize 直接用 item.price，而 item.price 本身就是「会员价基数」
// （未打折），导致「列表 ¥14.4 → 确认页 ¥15.9 → 实收 ¥14.4」的金额跳变。
{
  const confirmJs = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
  const summarizeBody = confirmJs.match(/function summarize\(items, paymentMethod\)\s*\{([\s\S]*?)\n\}/);
  assert.ok(summarizeBody, '必须能定位到 summarize 函数体');
  const body = summarizeBody[1];

  assert.ok(
    /calcMemberPrice\(/.test(body),
    'summarize 必须调用 calcMemberPrice 计算会员价，不得直接用 item.price（会漏掉折扣）'
  );
  assert.ok(
    /originalPrice\s*\|\|\s*item\.price/.test(body),
    'summarize 必须以商品原价（originalPrice）为打折基数，与卡片/弹层/后端一致'
  );
  assert.ok(
    /calcMemberPrice\s*=\s*require/.test(confirmJs.replace(/\n\s*/g, ' ')) ||
      /require\(['"]\.\.\/\.\.\/utils\/member-level['"]\)/.test(confirmJs),
    '确认订单页必须复用 utils/member-level 的会员价函数，不得自行实现折扣逻辑'
  );
}

// ---------- 16. 「我的」页资料回写必须是合并式，不得清空资产字段 ----------
// 历史 bug：profile.js 的 fetchMe 回调用 saveUserProfile({nickname,avatar,phone,region})
// 重建缓存，而 saveUserProfile 内部是 Object.assign({}, EMPTY_PROFILE, source) ——
// 未传字段回落空壳的 0。导致每次从其它页返回「我的」页，
// 时光币/储值余额/优惠券/礼品卡全部清零（从礼品卡页返回最容易复现）。
{
  const profileJs = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');
  // 回写必须基于当前缓存合并：Object.assign({}, userProfile, {...})。
  // 用「两段特征同时存在」断言而非跨多行的脆弱正则。
  assert.ok(
    profileJs.includes('Object.assign({}, userProfile, {'),
    'profile 页资料回写必须基于当前缓存合并（Object.assign({}, userProfile, ...)），不得重建导致资产字段清零'
  );
  assert.ok(
    profileJs.includes('remote.points'),
    '资料回写必须包含远端 points 字段'
  );

  // 礼品卡订单页必须兼容 PageResult（{records:[...]}）：
  // 后端 myOrders 返回分页对象，Array.isArray 判断会把有数据的分页当空数组，
  // 表现为「订单页永远无数据」，与「我的礼品卡」的 3 条对不上。
  const ordersJs = fs.readFileSync(path.join(root, 'pages/gift-card-orders/gift-card-orders.js'), 'utf8');
  assert.ok(
    /orders\.records/.test(ordersJs),
    '礼品卡订单页必须兼容 PageResult.records 形态，否则订单永远显示为空'
  );

  // 「我的」页优惠券数量必须来自统一远端刷新方法，且只统计 UNUSED 可用券
  assert.ok(
    profileJs.includes("refreshCouponsFromRemote('UNUSED')"),
    '「我的」页优惠券数量必须调用 refreshCouponsFromRemote(UNUSED)'
  );
}

// ---------- 17. 优惠券列表与礼品卡页必须使用同一远端数据源 ----------
{
  const couponListJs = fs.readFileSync(path.join(root, 'pages/coupon-list/coupon-list.js'), 'utf8');
  const couponListWxml = fs.readFileSync(path.join(root, 'pages/coupon-list/coupon-list.wxml'), 'utf8');
  const couponUtils = fs.readFileSync(path.join(root, 'utils/coupons.js'), 'utf8');
  const giftOrdersJs = fs.readFileSync(path.join(root, 'pages/gift-card-orders/gift-card-orders.js'), 'utf8');
  const giftOrdersWxml = fs.readFileSync(path.join(root, 'pages/gift-card-orders/gift-card-orders.wxml'), 'utf8');

  assert.ok(
    couponListJs.includes("refreshCouponsFromRemote('UNUSED')"),
    '优惠券列表 onShow 必须刷新后端 UNUSED 券，不能只读旧缓存'
  );
  assert.ok(couponListJs.includes('loadError'), '优惠券列表必须记录加载失败状态');
  assert.ok(couponListJs.includes('retryLoad'), '优惠券列表必须提供失败重试方法');
  assert.ok(couponListWxml.includes('bindtap="retryLoad"'), '优惠券列表失败态必须绑定重试入口');
  assert.ok(
    /id:\s*item\.id\s*!=\s*null/.test(couponUtils),
    '优惠券展示 ID 必须以用户券主键为准，避免模板 code 重复导致列表覆盖'
  );
  assert.ok(
    /expiryText:[\s\S]*expireAt/.test(couponUtils),
    '优惠券有效期展示必须使用后端 expireAt 字段'
  );

  assert.ok(giftOrdersJs.includes('fetchGiftCardOrders'), '礼品卡页必须加载购买订单');
  assert.ok(giftOrdersJs.includes('fetchMyGiftCards'), '礼品卡页必须加载 ACTIVE 持有卡');
  assert.ok(giftOrdersJs.includes("status === 'ACTIVE'"), '礼品卡页只能展示未核销的 ACTIVE 持有卡');
  assert.ok(giftOrdersJs.includes('filteredGiftCards'), '礼品卡页必须提供持有卡过滤结果');
  assert.ok(giftOrdersJs.includes('filterGiftCards'), '礼品卡页必须支持按卡名和卡号搜索持有卡');
  assert.ok(giftOrdersJs.includes('loadError'), '礼品卡页必须记录接口失败状态');
  assert.ok(giftOrdersJs.includes('retryLoad'), '礼品卡页必须提供失败重试方法');
  assert.ok(giftOrdersWxml.includes('我的礼品卡'), '礼品卡页必须新增持有卡分区');
  assert.ok(giftOrdersWxml.includes('bindtap="retryLoad"'), '礼品卡页失败态必须绑定重试入口');
  assert.ok(!/名称或订单号/.test(giftOrdersWxml), '搜索占位文案必须同时提示卡名、卡号和订单号');
}

console.log('点单页空菜单健壮性、分类标签、onShow 统一刷新、底部留白、分类栏几何、隐藏入口、下单安全口径、clientAmount 单位、确认页金额口径与资产字段合并回写回归测试通过');
