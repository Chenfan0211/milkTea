# 舌诊「饮食推荐 + 一键加购」Implementation Plan

> 后续调整：门店选择改为独立页面；探测半径曾试扩至 20 公里，现已按产品要求改回严格 5 公里，见 `2026-10-18-recommendation-store-page.md`。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在舌诊报告详情「饮食 → 养生饮茶」区展示推荐理由、支持整区一键加购并按门店可售情况排序选店，选定门店后自动把有售商品加入购物车，可直接结算。

**Architecture:** 小程序端独立完成，不改后端。报告页负责展示推荐理由、商品详情入口与一键加购入口；点单页承接一次性交接数据，定位后对 5 公里内门店并发拉取现有门店菜单接口做可售探测，按「5 公里内整组有售优先，其余按距离」排序；选定门店后用默认规格构建购物车条目，缺货商品提示已下架。

**Tech Stack:** 微信小程序（Skyline + webview 混合）、原生 WXML/WXSS/JS、Node 断言脚本测试、Lucide 静态图标。

**Spec:** `user-h5/docs/tongue-redesign-spec.md`（本次同步更新）

## Global Constraints

- 只改 `user-h5` 小程序端；不改后端 API、DTO、数据库、网关与部署配置。
- 保留工作区现有未提交改动，只触碰本方案列出的文件。
- 颜色、字号、圆角、阴影只能取 `app.wxss page` 中的 token；间距取 4/8/12/16/20/24/32/40rpx；单位一律 rpx。
- 禁止原生 `<button>`、第三方 UI 组件库、远程 CDN、外部字体；图标统一 Lucide 本地资源。
- 可点击元素必须补 `aria-role="button"` 与语义化 `aria-label`。
- 主包新增页面必须用 `withShare` 包装，并同步 `app.json`、`scripts/check-project.mjs`、`utils/share.js`。
- 新增/修改后必须在 `user-h5` 执行 `npm run check`、`npm run test:acceptance`、`node scripts/check-project.mjs`。

---

## Task 1: 饮食推荐卡展示推荐理由与入口

**Files:**

- Modify: `user-h5/utils/tongue-report-model.js`
- Modify: `user-h5/packageHealth/pages/report/report.js`
- Modify: `user-h5/packageHealth/pages/report/report.wxml`
- Modify: `user-h5/packageHealth/pages/report/report.wxss`
- Test: `user-h5/scripts/tongue-report-model.test.mjs`
- Test: `user-h5/scripts/tongue-detection.test.mjs`

**Interfaces:**

- Produces: 商品卡字段 `{ id, productId, name, image, price, tags, constitutions, reason }`
- Produces: `handleAddAllProducts(event)`（写入 `app.globalData.pendingRecommendationAdd` 后 `wx.switchTab`）
- Produces: `openProductDetail(event)`（跳 `/pages/product-detail/product-detail?productId=`）

- [ ] 商品卡模型补 `productId` 与清洗后的 `reason`（去掉可选「【推荐理由】」前缀），未匹配商品仍被过滤。
- [ ] 卡片新增「推荐理由：…」文本行，整卡可点击进入商品详情。
- [ ] 养生饮茶区块标题行新增「一键加购」胶囊按钮，点击构造一次性交接数据并切换点单 Tab。
- [ ] 更新 `tongue-report-model.test.mjs` 与 `tongue-detection.test.mjs` 中「不展示推荐理由」的旧断言。
- [ ] 运行对应 Node 测试通过。

## Task 2: 消费端商品详情页

**Files:**

- Create: `user-h5/pages/product-detail/product-detail.js|json|wxml|wxss`
- Modify: `user-h5/app.json`
- Modify: `user-h5/scripts/check-project.mjs`
- Modify: `user-h5/utils/share.js`

**Interfaces:**

- Consumes: `api.fetchProductDetail(productId)`、`normalizeSpecProduct`
- Produces: 路由 `pages/product-detail/product-detail?productId=<id>`

- [ ] 页面只读展示主图、名称、标签、会员价/原价、描述、原料、过敏原、杯型、提示、规格组。
- [ ] 分「缺少编号 / 加载中 / 加载失败可重试 / 正常」四态。
- [ ] 注册页面清单与分享标题、`productId` 安全分享参数。
- [ ] 运行 `node scripts/check-project.mjs` 通过。

## Task 3: 推荐加购工具与购物车条目复用

**Files:**

- Modify: `user-h5/utils/cart.js`
- Create: `user-h5/utils/recommendation-cart.js`
- Test: `user-h5/scripts/tongue-recommendation-cart.test.mjs`

**Interfaces:**

- Produces: `buildCartItem(payload)`、`mergeCartItem(items, item)`
- Produces: `normalizeRequestItems(products)`
- Produces: `probeStoreAvailability(stores, productIds, { fetchMenu, concurrency })`
- Produces: `annotateAndSortStores(stores, availability, radiusKm)`
- Produces: `buildRecommendationCartItems(menuTabs, requestItems)`

- [ ] 抽出购物车条目构造函数与同规格合并逻辑，规格弹层与一键加购共用。
- [ ] 实现 5 公里内门店可售探测（并发 3、会话缓存、探测失败记 unknown，不污染全局菜单镜像）。
- [ ] 实现「5 公里内全有售优先、其余按距离」排序与徽标文案。
- [ ] 实现默认规格选品与购物车条目构建、缺货清单输出。
- [ ] 运行新增 Node 测试通过。

## Task 4: 点单页一键加购链路与门店徽标

**Files:**

- Modify: `user-h5/pages/menu/menu.js`
- Modify: `user-h5/pages/menu/menu.wxml`
- Modify: `user-h5/pages/menu/menu.wxss`
- Modify: `user-h5/components/store-list-card/store-list-card.js`
- Modify: `user-h5/components/store-list-card/store-list-card.wxml`
- Modify: `user-h5/components/store-list-card/store-list-card.wxss`
- Test: `user-h5/scripts/menu-spec-edit.test.mjs`
- Test: `user-h5/scripts/store-list-card.test.mjs`

**Interfaces:**

- Consumes: `app.globalData.pendingRecommendationAdd`、`utils/recommendation-cart.js`
- Produces: 门店卡可选属性 `availability: { text, tone }`
- Produces: 推荐模式选店加购、缺货 toast、旧下架条目自动取消勾选

- [ ] `onShow` 消费一次性交接数据，定位并完成门店可售探测与排序后打开门店选择层。
- [ ] 搜索、清空搜索、切城市返回都复用同一套排序与徽标。
- [ ] 推荐模式下选店先校验商品，有售加入购物车、缺货 toast「已下架」，全缺货保持选店层。
- [ ] 取消/返回清空推荐模式；旧的下架条目取消勾选，保证结算不再被阻塞。
- [ ] 扩展对应 Node 测试并运行通过。

## Task 5: 文档同步与全量验证

**Files:**

- Modify: `user-h5/docs/tongue-redesign-spec.md`
- Modify: `user-h5/package.json`

- [ ] 更新设计文档中商品卡与加购链路口径。
- [ ] 新测试加入 `check` 脚本。
- [ ] 运行 `npm run check`、`npm run test:acceptance`、`node scripts/check-project.mjs`。
