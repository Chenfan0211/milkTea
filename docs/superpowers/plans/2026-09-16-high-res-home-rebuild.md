# 微信小程序高清首页重建实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `user-h5` 的运行素材统一替换为本地 3x 高清资源，重建首页下半屏组件，并补齐素材、包体和开发工具排查校验。

**Architecture:** 保留 Skyline、现有页面路由、Mock 数据和 Lucide SVG；素材由 `scripts/build-images.mjs` 负责从临时超分工具生成或复用合格产物，运行页面只引用 `assets/images/3x`。`check-project.mjs` 统一校验运行引用、关键图片尺寸、包体图片总量和首页结构，页面层仅对首页结构做重建，对菜单和我的页做资源路径替换。

**Tech Stack:** 微信小程序 WXML/WXSS/JavaScript、Node.js ESM 脚本、Upscayl/Real-ESRGAN 可配置外部命令、Lucide Static 1.46.0。

**Spec:** 用户在 2026-09-16 提供并确认的《微信小程序报错修复与首页高清重建方案》。

## Global Constraints

- 不修改后端接口、页面路由、Mock 数据结构或业务交互范围。
- 运行图片必须来自 `assets/images/3x`，不得引用 `assets/temp`、远程 URL 或其他图标库。
- Lucide SVG 继续由 `npm run icons` 从 `lucide-static@1.46.0` 同步生成。
- `design/reference`、`scripts`、`docs` 和 `assets/temp` 必须通过 `project.config.json.packOptions.ignore` 排除出小程序包。
- 不添加 WebAPI 错误屏蔽、全局 Console 过滤或业务 `try/catch`。
- 不执行 Git 暂存或提交。

### Task 1: 写入高清素材验收测试

**Files:**
- Create: `user-h5/scripts/acceptance-check.test.mjs`

- [x] 写入关键图片尺寸、图片总量、运行引用、包体排除和首页结构断言。
- [x] 运行 `node scripts/acceptance-check.test.mjs`，确认当前代码因 `assets/temp` 和首页结构失败。

### Task 2: 素材流水线、包体配置与项目校验

**Files:**
- Create: `user-h5/scripts/build-images.mjs`
- Modify: `user-h5/scripts/check-project.mjs`
- Modify: `user-h5/project.config.json`
- Modify: `user-h5/package.json`

- [x] 实现可配置的 Upscayl/Real-ESRGAN 调用和合格 3x 产物复用。
- [ ] 加入关键 JPEG 尺寸、文件大小、运行引用、包体忽略和总量检查。
- [ ] 加入 `npm run build:images` 与 `npm run test:acceptance` 命令。

### Task 3: 统一页面高清素材引用

**Files:**
- Modify: `user-h5/data/mock.js`
- Modify: `user-h5/pages/menu/menu.wxml`
- Modify: `user-h5/pages/profile/profile.wxml`

- [ ] 将商品图、头像、菜单 Banner、我的页主视觉和 Banner 全部切到 `assets/images/3x`。
- [ ] 保持现有数据键、路由和点击行为不变。

### Task 4: 重建首页

**Files:**
- Modify: `user-h5/pages/home/home.wxml`
- Modify: `user-h5/pages/home/home.wxss`

- [ ] 使用 3x 主视觉并保留原稿首屏构图。
- [ ] 以 WXML/WXSS 重建用户栏、点单模式、快捷入口和加盟合作区。
- [ ] 固定左右边距、圆角、区块高度和 TabBar 安全空间。

### Task 5: 开发环境排查文档与规范

**Files:**
- Create: `user-h5/docs/development-tool-troubleshooting.md`
- Modify: `AGENTS.md`

- [ ] 记录工具版本、基础库、AppID、三层隔离复现步骤、缓存清理和结论。
- [ ] 将高清素材运行规范和资源检查命令写入项目约定。

### Task 6: 验证

- [ ] 运行 `npm run icons`。
- [ ] 运行 `npm run check`。
- [ ] 运行 `npm run test:acceptance`。
- [ ] 执行 JavaScript 语法检查和包体统计。
- [ ] 检查微信开发者工具 CLI 是否可用；若不可用，记录未执行原因，不改变 Skyline 配置。
