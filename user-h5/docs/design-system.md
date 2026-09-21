# milkTea 小程序 UI 设计规范

> 适用范围：`user-h5` 小程序端所有页面与组件。
> 基线页面：首页（`pages/home`）、点单（`pages/menu`）、我的（`pages/profile`）、订单（`pages/orders`）。
> 本文档是 UI 开发的唯一依据；实现与本文档冲突时，以本文档为准。

## 一、设计原则

1. **品牌调性**：新中式养生茶饮，清爽、自然、克制。
2. **唯一主色**：品牌绿 `#53882C` 是全站唯一强调色，用于主按钮、选中态、关键数字与状态。
3. **大留白、弱阴影**：用留白和层级建立秩序，不用重描边、不用重阴影。
4. **每屏一个视觉重心**：首屏只允许一个大图 / 大数字 / 主卡片作为焦点。
5. **一致性优先**：同类元素在不同页面必须使用同一套 token 与同一种结构。

## 二、设计 Token

> **档位豁免说明**：当 `user-h5/scripts/*.test.mjs` 验收测试或参考图明确固化了某个精确像素值（如 `18rpx` 字号、`14rpx/15rpx/6rpx` 间距、`#2D2D2D` 字面量等）时，该值优先于本章的字号/间距/圆角档位规范，用于精确还原参考图；其余未固化的取值仍必须严格套用档位与 token。
>
> **当前已登记的间距豁免值**（2026-09-21 全量排查后归档，均为参考图还原所需，禁止再扩散到新代码）：
>
> | 位置 | 值 | 用途 |
> | --- | --- | --- |
> | `pages/home/home.wxss` `.user-strip` | `padding: 0 62rpx` | 首页用户信息栏左右留白对齐参考图 |
> | `pages/menu/menu.wxss` `.menu-tabs__item` | `margin-right: 58rpx` | 点单顶部菜单 Tab 间距对齐参考图 |
> | `pages/profile-data/profile-data.wxss` `.profile-data-scroll__inner` | `padding-top: 100rpx` | 个人资料页顶部让位参考图（同规则内的 `280rpx` 已被验收测试固化） |
>
> 新增类似取值前必须先确认参考图依据，并在本表登记；未登记的非档位间距一律视为违规。

所有 token 定义在 `user-h5/app.wxss` 的 `page` 选择器内。新代码必须通过 `var(--token)` 引用，禁止写死字面量。

### 2.1 颜色

| Token                  | 值        | 用途                                                                     |
| ---------------------- | --------- | ------------------------------------------------------------------------ |
| `--brand-green`        | `#53882C` | 主色：主按钮、选中态、下划线、状态文字、关键数字                         |
| `--brand-dark`         | `#3F6E1F` | 主色深阶：按压态、深色标题                                               |
| `--brand-soft`         | `#E9F1D9` | 主色浅底：标签底、图标底、浅色区块                                       |
| `--brand-tint`         | `#E7F0D8` | 页面级主视觉衬色（如首页大图背景延续）                                   |
| `--page-bg`            | `#F4F5F2` | 页面底色默认值，带一点品牌绿调                                           |
| `--page-bg-neutral`    | `#F7F7F7` | 纯中性灰页面底色，用于以卡片列表为主、需要白卡片更突出的页面（如订单页） |
| `--card-bg`            | `#FFFFFF` | 卡片、容器、浮层底色                                                     |
| `--text-main`          | `#2F302D` | 主文字：标题、正文、金额                                                 |
| `--text-secondary`     | `#777873` | 次要文字：说明、副标题、规格描述                                         |
| `--text-muted`         | `#9B9B96` | 弱化文字：占位、未选中 TabBar、辅助标注                                  |
| `--line-color`         | `#E9EAE5` | 分割线、描边、浅色边界                                                   |
| `--dark-fill`          | `#2D2D2D` | 深色填充：悬浮购物车条等                                                 |
| `--price-red`          | `#FF0000` | 商品价格、促销价、优惠标签                                               |
| `--tag-hot-bg`         | `#FFE0E1` | 「年度热销」类标签底色                                                   |
| `--tag-light-bg`       | `#D5F0C3` | 茶类标签底色                                                             |
| `--tag-light-text`     | `#6E9B45` | 茶类标签文字                                                             |
| `--tag-gold-text`      | `#9A6B00` | 金色角标文字（购物车新品/优惠标签）                                      |
| `--tag-gold-bg`        | `#FFF8DF` | 金色角标浅底                                                             |
| `--tag-gold-bg-strong` | `#FFF3B8` | 金色角标深一档底色                                                       |
| `--accent-orange`      | `#C65A1E` | 营销提醒、限时提示（低频使用）                                           |
| `--queue-warning`      | `#E6A23C` | 排队 6–10 杯的等待黄色状态                                               |
| `--favorite-gold`      | `#D4A017` | 已收藏门店的金色五角星状态                                               |

规则：

- 一个语义只对应一个 token，禁止新增近似色。`#30312E`、`#8B8E87`、`#8B8C87` 这类历史近似值不得出现在新代码中，遇到时按本表归并。
- 唯一允许直接书写的颜色是白色前景 `#FFFFFF`（用于主按钮文字、深色底上的文字）与阴影中的 `rgba()`。
- 组件属性例外：`navigation-bar` 的 `background` / `color` 等组件入参无法引用 CSS 变量，允许书写与 token 等值的字面量；该例外只适用于组件属性，不适用于 WXSS。
- 透明度统一用 `rgba()` 表达，不要使用 8 位 hex。

#### 2.1.1 门店卡 · 方案 D 鲜绿活力风（参考图固化值）

门店列表卡（点单页门店选择层、收藏门店、券适用门店共用）按参考图「方案 D」固化了以下 token；它们只服务于门店卡，不改动上面的全站品牌色。

| Token                     | 值        | 用途                                   |
| ------------------------- | --------- | -------------------------------------- |
| `--store-page-mint`       | `#EAF4E1` | 门店选择层列表底色（白卡片衬底）       |
| `--store-card-title`      | `#245C2B` | 门店名深绿标题                         |
| `--store-card-ink`        | `#75886A` | 卡片内的地址、营业时间、距离胶囊文字   |
| `--store-card-accent`     | `#2E8B40` | 排队状态文字与圆点                     |
| `--store-pill-bg`         | `#EDF5E2` | 距离胶囊底色                           |
| `--store-promo-from`      | `#429E46` | 上新横幅渐变起点（左）                 |
| `--store-promo-to`        | `#2E7D3A` | 上新横幅渐变终点（右）                 |
| `--store-action-from`     | `#3B9544` | 电话/导航圆形按钮渐变起点（上）        |
| `--store-action-to`       | `#32843D` | 电话/导航圆形按钮渐变终点（下）        |
| `--store-decor-leaf`      | `#A6C685` | 卡片右下角叶芽水印（允许用 opacity 降淡） |

同一张卡片的固化为：白底 `--card-bg`、`min-height: 372rpx`、内边距 `20rpx 24rpx`、卡片间距 `32rpx`、圆角 `--radius-lg`；店名 `--font-xl`、横幅与地址/时间/胶囊文字 `--font-caption` / `--font-sm`、排队行 `--font-base`；电话与导航按钮 72rpx 圆形、间距 24rpx、定位在右 24rpx / 下 20rpx。

### 2.2 字号与字重

字号只有 7 档：

| Token            | 值      | 用途                               |
| ---------------- | ------- | ---------------------------------- |
| `--font-caption` | `20rpx` | 角标、极小标签、辅助标注           |
| `--font-sm`      | `24rpx` | 次要说明、辅助信息、小标签         |
| `--font-base`    | `28rpx` | 正文基准（与 `page` 默认字号一致） |
| `--font-md`      | `30rpx` | 列表主文案、商品名、卡片标题       |
| `--font-lg`      | `34rpx` | 区块标题、页面标题                 |
| `--font-xl`      | `38rpx` | 大数字、金额、强调数值             |
| `--font-display` | `48rpx` | 空状态、特殊场景大字号             |

字重只有两档：

- `400`：正文与说明，默认字重，不写也可。
- `600`：标题、按钮、关键数字、选中态。

行高：正文按 `1.4` 左右取整（如 `28rpx` 字号配 `40rpx` 行高）；标题按 `1.2` 左右。

### 2.3 圆角

| Token           | 值       | 用途                     |
| --------------- | -------- | ------------------------ |
| `--radius-xs`   | `4rpx`   | 小标签、状态角标         |
| `--radius-sm`   | `8rpx`   | 图片、缩略图、方格图     |
| `--radius-md`   | `16rpx`  | 列表卡片、输入框         |
| `--radius-lg`   | `24rpx`  | 内容卡片、弹层、大图容器 |
| `--radius-pill` | `999rpx` | 胶囊按钮、圆形图标底     |

头像与正圆形图标底直接写 `border-radius: 50%`。

### 2.4 阴影

| Token            | 值                                    | 用途                     |
| ---------------- | ------------------------------------- | ------------------------ |
| `--shadow-card`  | `0 8rpx 28rpx rgba(49, 73, 28, 0.06)` | 内容卡片、列表卡         |
| `--shadow-float` | `0 12rpx 30rpx rgba(0, 0, 0, 0.22)`   | 悬浮元素：购物车条、浮层 |

同一屏内阴影层级不超过两层；页面级区块不再叠加额外阴影。

### 2.5 间距与尺寸

间距（`padding` / `margin` / 行内间隔）只取以下 8 档：`4 / 8 / 12 / 16 / 20 / 24 / 32 / 40rpx`。

| 场景               | 取值                            |
| ------------------ | ------------------------------- |
| 页面左右边距       | `20rpx`（`var(--page-gutter)`） |
| 卡片内边距         | `24rpx`                         |
| 卡片之间的间距     | `20rpx`                         |
| 区块标题与内容间距 | `16 ~ 24rpx`                    |
| 图标与文字间距     | `8rpx`                          |
| 列表行上下间距     | `16 ~ 20rpx`                    |

关键尺寸：

| 项               | 值                                                 |
| ---------------- | -------------------------------------------------- |
| 底部 TabBar 高度 | `112rpx`（`var(--tabbar-height)`），额外叠加安全区 |
| 主按钮高度       | `80rpx`（大）/ `48rpx`（小）                       |
| 点单商品图       | `150 × 200rpx`                                     |
| 用户头像         | `112rpx`                                           |

单位：一律使用 `rpx`，禁止 `px`（`env(safe-area-inset-bottom)` 除外）。

## 三、页面骨架

新页面只能从以下两种模式中选择，不得自创第三种。

### 模式 A：整页滚动型

适用：内容流式增长、整页一起滚动的页面（首页、我的页）。

```xml
<scroll-view class="page-scroll" scroll-y enhanced="{{false}}">
  <view class="page-body">
    <!-- 页面内容 -->
    <view class="tabbar-safe-space"></view>
  </view>
</scroll-view>
```

```css
.page-scroll {
  height: 100vh;
  background: var(--page-bg);
}

.page-body {
  min-height: 100vh;
  padding-bottom: 0;
  background: var(--page-bg);
}
```

要点：

- `scroll-view` 必须有固定高度 `100vh`，这是竖向滚动生效的前提。
- 内容末尾必须放 `tabbar-safe-space`，避免最后一块内容被 TabBar 遮挡。
- 页面根节点不得使用 `overflow: hidden` 锁死纵向滚动。

### 模式 B：三段式固定型

适用：需要固定头部 / 页签，且中间区域独立滚动的页面（点单、订单页）。

```xml
<view class="page-shell">
  <navigation-bar title="页面标题" background="#FFFFFF" color="#2F302D" />

  <!-- 可选：固定页签 -->
  <view class="page-tabs"></view>

  <scroll-view class="page-scroll" scroll-y>
    <view class="page-scroll__inner">
      <!-- 列表内容 -->
      <view class="tabbar-safe-space"></view>
    </view>
  </scroll-view>
</view>
```

```css
.page-shell {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100vh;
  overflow: hidden;
  background: var(--page-bg);
}

.page-scroll {
  flex: 1;
  min-height: 0;
}
```

要点：

- 根节点固定 `100vh` + `overflow: hidden`，只让中间区域滚动。
- 滚动区必须同时声明 `flex: 1` 与 `min-height: 0`，否则会被内容撑开而失去滚动能力。

### Tab 页统一约定

Tab 页（首页 / 点单 / 会员专区 / 订单 / 我的）必须在 `onShow` 同步选中态：

```js
onShow() {
  if (this.getTabBar) this.getTabBar().setData({ selected: 0 }) // 首页0 点单1 会员专区2 订单3 我的4
}
```

底部导航统一使用原生 `open-type="switchTab"`，禁止改成 `bindtap` + `wx.switchTab`。

### 底部安全区

`.tabbar-safe-space` 已全局定义在 `app.wxss`：

```css
.tabbar-safe-space {
  height: calc(var(--tabbar-height) + env(safe-area-inset-bottom));
}
```

直接使用该 class，禁止在页面内重复定义。

## 四、组件与元素

### 卡片

- 底色 `var(--card-bg)`，内边距 `24rpx`，卡片间距 `20rpx`，阴影 `var(--shadow-card)`。
- 圆角按密度选择：列表卡 `var(--radius-md)`，内容卡 / 大图卡 `var(--radius-lg)`。
- 卡片内部分割线用 `var(--line-color)`，高度 `1rpx`。

### 按钮

- 主按钮：`var(--brand-green)` 底 + `#FFFFFF` 字 + `var(--radius-pill)`，高度 `80rpx`（大）或 `48rpx`（小）。
- 次按钮：`var(--card-bg)` 底 + `1rpx` `var(--line-color)` 描边 + `var(--text-main)` 字。
- 统一用 `<view>` + `bindtap` 实现，禁止使用原生 `<button>`：其默认样式与 `::after` 边框会污染设计。

### 状态标签与角标

- 描边标签：`1 ~ 2rpx` `var(--brand-green)` 描边 + 同色文字 + `var(--radius-xs)`，字号 `var(--font-sm)`。
- 角标：`var(--price-red)` 或 `var(--brand-green)` 底 + 白字 + `var(--radius-pill)`。

### 列表

- 行高由内容决定，不写死高度；行与行之间用 `var(--line-color)` 分隔线或 `20rpx` 间距。
- 单行文本统一加省略号，可直接使用全局 `.ellipsis`；两行截断使用 `.ellipsis-2`。
- 图片按语义选择 `mode`：完整展示用 `aspectFit`，铺满裁切用 `aspectFill`。

### 空状态

- 复用 `components/empty-state`，不要重复实现。
- 文案结构：标题说明"为什么空"，描述说明"接下来能做什么"。

### 导航栏

- 复用 `components/navigation-bar`，标题通过 `title` 传入。
- 单页差异（如标题两侧留白）通过 `ext-class` 覆盖，禁止复制一份组件。
- 项目统一使用 `navigationStyle: custom`，不要依赖系统导航栏。

### 悬浮元素

- 悬浮购物车条等使用 `position: fixed` + `var(--shadow-float)`。
- 底部悬浮元素必须叠加安全区，例如：`bottom: calc(var(--tabbar-height) + env(safe-area-inset-bottom) + 18rpx)`。

## 五、图标与素材

沿用 `AGENTS.md` 的《图标规范》与《高清素材规范》，此处不重复展开。核心约束：

- 图标一律使用 Lucide，运行时引用 `assets/icons/lucide/*.svg`。
- 运行图片一律引用 `assets/images/3x/*`，禁止引用 `assets/temp`。
- 新增图标时先改 `scripts/sync-lucide-icons.mjs` 再执行 `npm run icons`。

## 六、交互反馈与无障碍

- 未接入的功能统一提示：

```js
wx.showToast({ title: `${label}暂未接入`, icon: 'none' });
```

- 可点击元素必须补 `aria-role="button"` 与语义化 `aria-label`。
- 承载信息的图片需要 `aria-label`；纯装饰图片不写。
- 文案简短、动词开头（如「立即点单」「切换门店」），不使用感叹号堆叠。

## 七、禁止事项

1. 新代码中出现字面量颜色（`#xxx` / `rgb()`）；白色前景与阴影 `rgba()` 除外。
2. 使用 `px` 单位。
3. 使用原生 `<button>` 承载主要交互。
4. 引入第三方 UI 组件库、远程 CDN、外部字体。
5. 用 emoji、手写 SVG、单个汉字充当图标。
6. 在页面内自建 TabBar，必须使用 `custom-tab-bar`。
7. 省略 `tabbar-safe-space`，导致内容被 TabBar 遮挡。
8. 在页面根节点写 `overflow: hidden` 锁死滚动（模式 B 的布局根节点除外）。

## 八、提交前验收清单

- [ ] 颜色、字号、圆角、阴影全部取自本文档 token，没有新增近似值。
- [ ] 间距取值落在 8 档内，页面左右边距使用 `var(--page-gutter)`。
- [ ] 页面骨架属于模式 A 或模式 B；Tab 页 `onShow` 选中态正确。
- [ ] 内容底部有 `tabbar-safe-space`。
- [ ] 图标来自 Lucide，图片来自 `assets/images/3x`。
- [ ] 可点击元素有 `aria-role` / `aria-label`；未接入功能用 `wx.showToast` 提示。
- [ ] 在 `user-h5` 目录执行 `npm run check` 通过。
