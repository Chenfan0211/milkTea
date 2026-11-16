# 首页轮播逐条管理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 首页轮播逐条管理，全部停用或删除时显示无跳转默认图。

**Architecture:** `home_banner` 表逐条保存，通用 CRUD 管理；首页配置接口只读取启用且未删除记录；小程序在空列表时使用本地默认图。

**Tech Stack:** Java/Spring JDBC、MySQL 8、Vue 3/Naive UI、微信小程序。

**Spec:** 本会话用户确认的首页轮播逐条管理方案。

## Global Constraints

- 保留工作区现有未提交改动。
- 小程序 UI 遵守 `user-h5/docs/design-system.md`，修改后运行 `npm run check`。
- 删除只设置 `deleted=1`，不物理删除记录或图片文件。

---

**目标：** 首页轮播按图片独立新增、编辑、启停、排序和逻辑删除；没有启用图片时显示无跳转的本地默认图。

**现状：** 首页存于 `app_config.home_hero` 的 JSON 数组；我的页轮播使用 `profile_banner` 和通用 CRUD，删除执行 `deleted=1`。本次复用后者的管理模式，保留旧首页数据。

## 任务 1：数据和接口

- [x] 先在 `AppConfigControllerTest` 增加首页只返回启用且未删除记录、空列表触发默认图的失败用例。
- [x] 新增 Flyway `home_banner` 表，字段为 `image_url/alt_text/target_type/target_id/sort/status/deleted`，从旧 `home_hero.banners` 或旧单图迁移非空图片。
- [x] 在 `CrudRegistry` 登记 `homeBanners`；`AppConfigController.home()` 从表读取轮播，保留 `hero.banners/imageUrl/altText` 响应形状。验证定向 Java 测试。

## 任务 2：后台管理

- [x] 在 `src/store/modules/admin/index.ts` 登记 `homeBanners`。
- [x] 将 `src/views/system/home-decor/index.vue` 改为与我的页相同的列表和编辑弹窗：图片预览、跳转目标、排序、状态、启停、带备注的逻辑删除。上传仍校验 JPG/PNG、5MB、至少 750×1040px。
- [x] 更新旧首页装修静态检查，运行 `npm run test:home-decor` 和 `npm run typecheck`。

## 任务 3：小程序兜底

- [x] 先在 `home-hero-config.test.mjs` 加入全停用/全删除时只显示默认图的失败用例；现有 `banner-target.test.mjs` 已覆盖 `none` 的导航行为。
- [x] `home-hero.js` 只接受可用记录，空列表回退 `home-hero.jpg`；首页视图为默认图取消可点击语义与跳转绑定。
- [x] 运行定向小程序测试、`node user-h5/scripts/check-project.mjs`、`npm run check`。

## 收尾

- [x] 核对迁移旧数据、排序、状态和逻辑删除契约；查看 diff，确认未覆盖工作区其他未提交改动。
- [x] 报告验证结果和未执行的数据库部署/真机验证。
