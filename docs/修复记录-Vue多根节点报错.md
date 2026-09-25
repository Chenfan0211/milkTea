# 修复：Vue Transition 多根节点报错（资源方管理页）

> 日期：2026-09-25
> 现象：打开「主体管理 / 资源方管理」页，全屏红色遮罩报错；控制台同样有 warning
> 报错原文：`[vite-plugin-vue-transition-root-validator] 检测到 Vue Transition 多根节点错误`
> 状态：✅ 已修复并上线

---

## 一、根因

`src/views/subject/channel/index.vue` 的 `<template>` 有**两个并列根元素**：

```html
<template>
  <AdminListPage ref="listRef" :config="config" />
  <ChannelStoreDialog v-model:show="dialogVisible" ... />
</template>
```

渲染出来是 **Fragment（多根）**，而页面切换动画 `<Transition>` 要求
「插槽内容只渲染一个元素根节点」，于是 Vue 运行时发出警告，
本项目安装的 `vite-plugin-vue-transition-root-validator` 捕获后弹出全屏遮罩。

### 为什么其他同类页面没报错

项目里还有 5 个页面同样是「列表 + 弹窗」结构，但它们都用 `<div class="page-root">` 包了一层，
所以是单根。**只有 `channel` 页漏了这个容器** —— 这正是本项目已确立的写法约定：

| 页面 | 是否包裹 |
|------|---------|
| `marketing/gift`、`marketing/member`、`marketing/stored`、`product/list`、`trade/order` | ✅ 有 `<div class="page-root">` |
| `subject/channel` | ❌ **缺失（本次修复）** |

---

## 二、修复

`src/views/subject/channel/index.vue`：加 `<div class="page-root">` 包住两个子节点，
与其余 5 个页面写法一致。

`page-root` 在本页无任何样式定义（纯语义包裹容器），且 `AdminListPage` 自带
`<div class="admin-list-page">` 根元素，故**不影响布局**。

---

## 三、过程中的一个关键发现（差点修错）

第一版修复我**把说明注释写在了 `<template>` 里面**：

```html
<template>
  <!-- 说明注释 -->
  <div class="page-root"> ... </div>
</template>
```

用真实 Vue 编译器一验，**根节点仍是 Fragment，报错依旧**。

原因：Vue 会把模板内的**顶层注释也算作一个根节点**，与 `<div>` 并列 → 仍是多根。
实测对照：

| 模板内容 | 编译后根节点 |
|---------|-------------|
| 顶层有注释 + 1 个 div | **Fragment** ❌ |
| 去掉注释，只有 1 个 div | 单个 div ✅ |

**修正**：注释移到 `<template>` **外面**。

> 教训：这类问题必须用**真实编译器**验证产物，肉眼看「只有一个 div」会得出错误结论。

---

## 四、新增 CI 守卫

| 文件 | 说明 |
|------|------|
| `scripts/vue-single-root.test.mjs` | 用真实 Vue 编译器判定每个页面的根节点 |
| `.github/workflows/vue-single-root.yml` | PR / push 时自动运行 |
| `package.json` | 新增 `check:vue-root`，并加入 `pre-commit` 钩子 |

### 判定方式（写错过一版，故记录）

只看 **render 函数首个 `return` 实际创建的是什么**：

| 产物 | 含义 |
|------|------|
| `createElementBlock(_Fragment, ...)` | 多根 ❌ |
| `createElementBlock("div", ...)` | 单元素根 ✅ |

**不能全文搜 `Fragment`**：模板里只要用了 `v-for`，编译器就会
`import { Fragment as _Fragment }`，全文匹配会**大面积误报**
（第一版实测误报 10 个完全正常的页面）。

---

## 五、验证

| 验证项 | 结果 |
|--------|------|
| **守卫有效性（双实验）** | 还原成双根 → 如期失败并报「2 个并列根元素」；模板内加注释 → 如期失败并报「顶层非元素节点」；恢复后 SHA256 一致 ✅ |
| Vue 编译器判定（dev + prod 模式） | 根节点均为 `createElementBlock("div", ...)`，非 Fragment ✅ |
| dev server 实际编译产物 | 无 Fragment，render 首行为 `_createElementBlock("div", _hoisted_1, [...])` ✅ |
| 扫描全部页面 | **59/59 通过，零误报** ✅ |
| `npm run typecheck` | exit 0 ✅ |
| oxlint（改动文件）| 0 错误 0 警告 ✅ |
| 生产构建 | 成功；channel chunk 含 `page-root` ✅ |

---

## 六、部署

| 项 | 内容 |
|----|------|
| 前端 | `/opt/wuling/web`（仅前端，后端与数据库未动）|
| 备份 | `/opt/wuling/backup/web-pre-rootfix-20260925-151628.tar.gz` |
| 回滚 | `rm -rf /opt/wuling/web && mv /opt/wuling/web.old-rootfix-20260925-151628 /opt/wuling/web && systemctl reload nginx` |

---

## 七、遗留说明

- 本次**未跟踪**其他 5 个多根页面的写法（它们本来正确），只修了报错的 `channel` 页。
- 守卫脚本会持续检查全部 59 个页面，将来新增页面若有同类问题会在 PR 阶段被拦下。
- `ChannelStoreDialog.vue` 仍属他人未提交的在途工作；本次改动只涉及它的父页面模板结构，**未改其内部逻辑**。