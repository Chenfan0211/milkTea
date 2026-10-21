# 门店选择独立页 + 可售数量优先排序 + 拍摄弹层修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 一键加购改为独立门店页（地图/城市/搜索/定位/返回），5 公里内按推荐商品可售数量优先排序；点单页只负责消费一次性购物车交接；修复拍摄页系统选择弹层失败无反馈。

**Architecture:** 报告页进入 `pages/recommendation-stores/recommendation-stores`，页面负责定位、门店可售探测、排序与选店；选定后写 `app.globalData.pendingRecommendationCart` 并 `switchTab` 点单页，由点单页合并购物车。拍摄页保留系统 action sheet，补失败兜底与 devtools 相册降级。

**Tech Stack:** 微信小程序（Skyline + 单页 webview map）、原生 WXML/WXSS/JS、Node 断言脚本、微信开发者工具自动化。

## Global Constraints

- 只改 `user-h5` 小程序端；不改后端 API、DTO、数据库、网关、部署。
- 保留工作区现有未提交改动。
- UI 只能用 `app.wxss page` token；间距走 4/8/12/16/20/24/32/40rpx 或已登记豁免；单位 rpx。
- 主包新增页面必须 `withShare`，同步 `app.json`、`scripts/check-project.mjs`、`utils/share.js`。
- 新增/修改后执行 `npm run check`、`npm run test:acceptance`、`node scripts/check-project.mjs`。

## Tasks

1. `utils/recommendation-cart.js`：探测结果补 `availableCount/totalCount`；排序改为 5 公里内有可售优先（数量降序 → 距离升序），其余距离升序；徽标按数量输出。
2. 新增 `pages/recommendation-stores/recommendation-stores.*`：webview map + cover-view 返回、城市、搜索、重新定位、推荐提示、分组门店列表、选店交接。
3. 报告页一键加购改为 `navigateTo` 新门店页；点单页移除推荐模式，新增 `pendingRecommendationCart` 消费与购物车合并。
4. 新增 `utils/tongue-capture.js`；拍摄页补 action sheet / chooseMedia / chooseImage 失败处理与 devtools 相册降级。
5. 更新/新增测试、文档，跑全量校验与开发者工具自动化验收。
