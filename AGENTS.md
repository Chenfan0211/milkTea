# milkTea 项目开发约定

## 读图规范

- Codex 读取图片前必须先检查原图尺寸；任一边超过 `2048px` 时，不得直接读取原图，必须先复制到系统临时目录，并把副本最长边缩小到 `2048px` 以内后再读取，从源头避免触发自动缩放。

## UI 设计规范

- 小程序所有页面与组件的 UI 实现，必须遵守 `user-h5/docs/design-system.md`，该文档是 UI 开发的唯一依据。
- 颜色、字号、圆角、阴影只能取设计文档中的 token（已定义在 `user-h5/app.wxss` 的 `page` 选择器内），禁止在新代码中写死近似色值与字号档位。
- 间距只取 `4 / 8 / 12 / 16 / 20 / 24 / 32 / 40rpx`，页面左右边距统一 `20rpx`；单位一律 rpx，禁止 px。
- 页面骨架只能选择设计文档中的「整页滚动型」或「三段式固定型」，不得自创第三种；Tab 页内容底部必须保留 `tabbar-safe-space`。
- 禁止使用原生 `<button>`、第三方 UI 组件库、远程 CDN 与外部字体。
- 可点击元素必须补 `aria-role="button"` 与语义化 `aria-label`；未接入功能统一用 `wx.showToast({ icon: 'none' })` 提示。
- 新增页面或组件后，必须在 `user-h5` 目录执行 `npm run check`。

## 图标规范

- `user-h5` 小程序端所有图标统一使用 [Lucide](https://lucide.dev/)。
- 图标源固定为 `user-h5/package.json` 中的 `lucide-static@1.46.0`，禁止使用其他图标库或远程 CDN。
- 禁止在页面中手写 SVG、使用 emoji、单个汉字或临时 PNG 充当业务图标。
- 运行图标同步：在 `user-h5` 目录执行 `npm run icons`。
- 新增图标时，先修改 `user-h5/scripts/sync-lucide-icons.mjs` 的图标映射，再运行同步命令。
- 运行时图标统一从 `user-h5/assets/icons/lucide/*.svg` 引用，禁止直接引用 `node_modules`。
- 生成后的 Lucide SVG 不手工修改；其中应保留 `@license lucide-static` 注释，并将 `currentColor` 固化为项目颜色。
- 常见颜色：普通灰色 `#9B9B96`，激活绿色 `#53882C`，正文图标 `#666762`，深色图标 `#747570`，白色图标 `#FFFFFF`。
- 修改图标或页面后必须执行 `node user-h5/scripts/check-project.mjs`，确保图标均来自 Lucide 且本地资源完整。

## 高清素材规范

- 小程序运行图片统一从 `user-h5/assets/images/3x` 引用，禁止在页面、Mock 数据或组件中引用 `user-h5/assets/temp`。
- 设计参考图放在 `user-h5/design/reference`，素材构建脚本放在 `user-h5/scripts`；两者均通过 `project.config.json.packOptions.ignore` 排除出小程序包。
- 新增或替换高清素材时，优先使用 `npm run build:images` 复用合格产物，或调用系统临时目录中的便携版 Upscayl/Real-ESRGAN 重新生成。
- 关键素材尺寸、文件大小、3x 图片总量、运行资源路径和首页结构由 `npm run check` 与 `npm run test:acceptance` 校验。
- 主包 3x 图片总量目标控制在 `1.8MB` 以下；超限时优先压缩 Banner 和普通背景，不降低首页主视觉、商品图和头像的清晰度。
- `touristappid + Skyline` 下若复现完全相同的 `webapi_getwaasyncconfiginfo:fail ""`，先按开发工具隔离流程记录；不得在业务代码中屏蔽 Console 或吞掉其他异常。
