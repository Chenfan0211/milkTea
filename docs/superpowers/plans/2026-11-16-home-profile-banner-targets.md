# 首页与我的页轮播跳转 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 首页支持多张装修图轮播，首页与我的页每张图可由运营配置功能页、点单商品或商城商品跳转。

**Architecture:** 首页沿用 `app_config.home_hero`，新增 `banners` 数组并兼容旧的单图字段。我的页沿用 `profile_banner` 表，增加目标字段。小程序用统一白名单跳转模块分流；商城按商品详情的上下架和库存判断，点单按当前门店的最新菜单判断。

**Tech Stack:** Vue 3/Naive UI、Java Spring JDBC/MySQL、微信小程序 WXML/WXSS/JS。

**Spec:** 本会话已确认的方案：首页多图、750×1040 图片等比完整显示、两处轮播可配置功能页与点单/商城商品、不可售回退相应列表。

## Global Constraints

- 保留工作区已有未提交改动，不重置、不提交其他人的文件。
- 小程序 UI 遵守 `user-h5/docs/design-system.md`，图标使用本地 Lucide。
- 首页上传原图宽高分别不少于 750×1040px，保持 750×1040rpx 展示区。
- 不接受任意 URL；跳转类型只能从后台固定选项选择。

---

### Task 1: 数据契约与后台编辑

**Files:** `src/views/system/home-decor/index.vue`、`src/views/marketing/profile-banner/`、`server/src/main/resources/db/migration/V100__banner_targets.sql`、`server/src/main/java/com/wuling/system/{crud/CrudRegistry.java,controller/AppConfigController.java}`。

**Interfaces:** `targetType` 为 `none/orders/coupons/recharge/menu/menuProduct/mallProduct`；`targetId` 为商品 ID。首页 `hero.banners` 为按顺序排列的数组；我的页每条记录带目标字段。

- [ ] 写失败用例：旧单图兼容、目标字段下发、两处编辑器显示并保存目标。
- [ ] 运行用例确认功能缺失导致失败。
- [ ] 实现多图编辑、图片尺寸校验、商品选择、数据库迁移和接口字段。
- [ ] 运行定向用例。

### Task 2: 首页轮播与统一跳转

**Files:** `user-h5/pages/home/home.{js,wxml,wxss}`、`user-h5/utils/{home-hero,banner-target}.js`、`user-h5/scripts/home-hero-config.test.mjs`、`user-h5/scripts/banner-target.test.mjs`。

**Interfaces:** `normalizeHomeBanners(hero, resolveAssetUrl)` 返回可展示图片；`openBannerTarget(item)` 按目标类型导航。

- [ ] 写失败用例：多图、旧配置、无效图、固定比例、功能页白名单、商城售罄与下架回退。
- [ ] 运行用例确认失败。
- [ ] 实现轮播与统一跳转。
- [ ] 运行定向用例。

### Task 3: 点单商品落地与我的页接入

**Files:** `user-h5/pages/menu/menu.js`、`user-h5/pages/profile/profile.{js,wxml}`、`user-h5/utils/profile-banner.js`、相关小程序测试。

**Interfaces:** `pendingBannerProductId` 为一次性点单商品跳转意图；点单页刷新门店菜单后消费，存在则打开现有商品详情规格层，不存在则留在列表。

- [ ] 写失败用例：目标保留、门店菜单校验、不可售停留列表、我的页逐图跳转。
- [ ] 运行用例确认失败。
- [ ] 实现最小逻辑并运行定向用例。

### Task 4: 验证与收尾

**Files:** 仅按验证失败修正本次相关文件。

- [ ] 更新旧版首页单图静态断言，运行 `node user-h5/scripts/check-project.mjs`、`npm run check`。
- [ ] 运行后台类型检查与定向 Java 测试。
- [ ] 审查 diff、确认未覆盖工作区原有修改，报告未验证的设备与部署范围。
