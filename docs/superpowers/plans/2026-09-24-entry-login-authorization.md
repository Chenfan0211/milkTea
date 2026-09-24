# 进入小程序即授权微信登录 实施方案

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> 日期：2026-09-24
> 需求：用户进入小程序就需要授权微信登录。
> 决策：**引导式 + 全部入口**（已与需求方确认）。

**Goal:** 让用户进入小程序时 100% 完成微信登录（静默、无感），并在启动页引导一次可跳过的手机号授权，覆盖冷启动 / 扫码 / 分享卡片 / 朋友圈全部入口。

**Architecture:** 保留现有三层（静默层 `ensureSilentLogin` / 登录层 `requireLogin` / 手机号层 `requirePhone`），新增「入口层 `utils/entry-login.js`」：`app.onLaunch` 调 `ensureEntryLogin()` 做带超时兜底的静默登录；`app.json` 加 `entryPagePath: pages/launch/launch`，让所有冷启动入口统一先过启动页，由启动页决定「直接 reLaunch 到目标页」还是「先进授权页引导绑手机号再 reLaunch 到目标页」。授权页新增 `mode=entry` 引导态，复用已有 UI 与 `flushPendingAction` 机制，不新增后端接口。

**Tech Stack:** 微信小程序原生（Skyline 默认渲染 + glass-easel 组件框架）、CommonJS 模块、Node.js 断言脚本测试（`node scripts/*.test.mjs`）、Lucide 本地图标。

**Spec:** `docs/login-authorization.md`（三层登录架构）+ 本次确认的「引导式 + 全部入口」决策。

## 一、背景与合规边界（必须先理解）

微信小程序「授权登录」分两层，能力完全不同：

| 层级 | 微信 API | 用户动作 | 能否强制 | 能拿到什么 |
|------|---------|---------|---------|-----------|
| A. 登录态 | `wx.login()` → `code` → 后端换 token | 无感，不需要点击 | ✅ 可以，不违规 | openid / unionid，能长期标识用户 |
| B. 手机号 / 头像昵称 | `<button open-type="getPhoneNumber">`、`chooseAvatar` | 必须用户点击确认 | ❌ **不能** | 手机号、微信头像昵称 |

两条硬约束：
1. `getPhoneNumber` / `chooseAvatar` 必须由用户点击行为触发，无法在 `onLaunch` 里程序化调起；
2. 小程序禁止在用户未使用服务前强制授权，强弹授权会被审核驳回甚至封禁「诱导授权」。

**因此本方案：静默登录做到 100%（无感）；手机号做成「进店即引导、可跳过、只引导一次」——两者叠加即是合规范围内能做到的最强效果。**

## 二、现状（已读代码确认）

- `app.js` 已有 `onLaunch → ensureSilentLogin()` 静默登录，失败不阻塞浏览（`loginFailed / loginError`）；
- 登录态唯一来源 `utils/auth-state.js`：`anonymous` / `authorized`（有 token 未绑手机号）/ `full`；
- 独立授权页 `pages/auth-login/auth-login`：微信一键手机号 + 短信降级 + 「暂不登录」；
- `utils/login-guard.js`：`ensureSilentLogin()` / `requireLogin()` / `requirePhone()`，已接入 7 个页面；
- **当前没有任何页面进入即跳授权页**，首页 / 点单 / 我的页均可匿名浏览。

## 三、Global Constraints

- UI 必须遵守 `user-h5/docs/design-system.md`；颜色 / 字号 / 圆角 / 阴影只能取 `app.wxss` 的 `page` 选择器内 token，禁止写死近似色值与字号档位。
- 间距只取 `4 / 8 / 12 / 16 / 20 / 24 / 32 / 40rpx`；页面左右边距统一 `20rpx`；单位一律 `rpx`，禁止 `px`。
- 页面骨架只能用设计文档的「整页滚动型」或「三段式固定型」；Tab 页内容底部必须保留 `tabbar-safe-space`。
- 禁止使用原生 `<button>`、第三方 UI 组件库、远程 CDN 与外部字体。
- 可点击元素必须补 `aria-role="button"` 与语义化 `aria-label`；未接入功能统一 `wx.showToast({ icon: 'none' })`。
- 图标统一 Lucide（`lucide-static@1.46.0`，运行时引用 `user-h5/assets/icons/lucide/*.svg`）；禁止手写 SVG / emoji / 汉字充当业务图标；新增图标先改 `scripts/sync-lucide-icons.mjs` 再执行 `npm run icons`。
- 运行图片统一从 `user-h5/assets/images/3x` 引用，禁止引用 `assets/temp`。
- **不得强制授权**：`getPhoneNumber` 只能由用户点击触发；引导页必须保留「暂不登录」，不得做成不绑手机号就无法使用。
- **启动页放行兜底**：静默登录超时或失败时必须继续跳转，绝不卡在启动页。
- 每次改动后必须在 `user-h5` 目录执行 `npm run check` 与 `npm run test:acceptance`。
- 新增页面必须同步更新 `scripts/check-project.mjs` 的 `expectedPages`（该断言是 `app.json.pages` 的严格逐项比对）。

---

## 四、改动清单（文件结构）

| 文件 | 动作 | 职责 |
|------|------|------|
| `user-h5/utils/entry-login.js` | 新增 | 入口静默登录（超时兜底）+ 引导去重 + 入口还原 |
| `user-h5/utils/entry-login.test` 对应 `scripts/entry-login.test.mjs` | 新增 | 上述能力单测 |
| `user-h5/pages/launch/*`（js/json/wxml/wxss） | 新增 | 极简启动页，唯一冷启动入口 |
| `user-h5/app.json` | 修改 | 注册启动页 + `entryPagePath` |
| `user-h5/app.js` | 修改 | `onLaunch` 改用 `ensureEntryLogin()` |
| `user-h5/pages/auth-login/*` | 修改 | 新增 `mode=entry` 引导态与 `from=launch` 返回逻辑 |
| `user-h5/pages/profile/profile.wxml|wxss|js` | 修改 | 未绑手机号时展示常驻引导条 |
| `user-h5/pages/home/home.wxml` | 修改 | 未登录时昵称位置显示「点击登录」并跳授权页 |
| `user-h5/utils/share.js` | 修改 | 启动页列入私密页（不参与分享） |
| `user-h5/scripts/check-project.mjs` | 修改 | `expectedPages` 补启动页 |
| `user-h5/scripts/role-function-pages.test.mjs` | 修改 | 补启动页 + 引导去重 + `entryPagePath` 断言 |
| `user-h5/package.json` | 修改 | `check` 链加入新测试 |
| `docs/login-authorization.md` | 修改 | 追加「进入即登录」章节 |

---

## 五、任务分解

### Task 1: 入口登录工具 `utils/entry-login.js`

**Files:**
- Create: `user-h5/utils/entry-login.js`
- Test: `user-h5/scripts/entry-login.test.mjs`
- Modify: `user-h5/utils/login-guard.js`（新增 `__resetForTest()` 测试钩子）
- Modify: `user-h5/package.json`

**Interfaces:**
- Consumes: `utils/login-guard.js#ensureSilentLogin()`、`utils/auth-state.js#getAuthState()`、`utils/user-profile.js#refreshUserProfileFromRemote()`。
- Produces:
  - `ENTRY_PROMPT_KEY = 'milkTea:auth:entry-prompted-at'`
  - `ENTRY_PROMPT_COOLDOWN = 12 * 60 * 60 * 1000`
  - `ENTRY_LOGIN_TIMEOUT = 1500`
  - `setPromptEnabled(enabled)` / `isPromptEnabled()`
  - `__resetForTest()` （仅测试用，清空会话标记）
  - `shouldPromptEntry(now?)` → boolean
  - `markEntryPrompted(now?)` → void
  - `ensureEntryLogin({ timeout })` → `Promise<{ ok, state, timedOut, error? }>`，**永不 reject**
  - `resolveEntryTarget(options?)` → string 完整路径（含 query）

- [x] **Step 1: 写失败测试**

创建 `user-h5/scripts/entry-login.test.mjs`：

```js
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

const storage = new Map();
let wxLoginCalls = 0;
let wxLoginBehavior = 'ok';

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
  login({ success, fail }) {
    wxLoginCalls += 1;
    if (wxLoginBehavior === 'ok') success({ code: 'code-' + wxLoginCalls });
    else fail(new Error('wx.login failed'));
  },
  request({ url, success, fail }) {
    if (url.indexOf('/auth/wx-login') >= 0) {
      if (wxLoginBehavior === 'ok') {
        success({ data: { code: 0, data: { token: 'token-1', userId: 1, openId: 'openid-1' }, message: 'ok' } });
        return;
      }
      fail(new Error('network down'));
      return;
    }
    // /auth/me
    success({ data: { code: 0, data: { id: 1, nickName: '测试用户', phone: '' }, message: 'ok' } });
  }
};

const entry = require(path.join(root, 'utils/entry-login.js'));
const auth = require(path.join(root, 'utils/auth.js'));
const guard = require(path.join(root, 'utils/login-guard.js'));

// 1. 未登录时 ensureEntryLogin 必须真正换取 token
const okResult = await entry.ensureEntryLogin();
assert.equal(okResult.ok, true, '静默登录成功时 ok 必须为 true');
assert.equal(okResult.timedOut, false, '未超时不得标记 timedOut');
assert.ok(auth.getToken(), '静默登录后必须写入 token');
assert.equal(okResult.state.hasToken, true, '返回态必须标记已登录');

// 2. 已登录时不得重复 wx.login（并发去重与缓存复用）
const callsBefore = wxLoginCalls;
await entry.ensureEntryLogin();
assert.equal(wxLoginCalls, callsBefore, '已登录时不得重复调用 wx.login');

// 3. 登录失败时永不 reject，且必须放行
auth.clearSession();
guard.__resetForTest();
wxLoginBehavior = 'fail';
const failResult = await entry.ensureEntryLogin();
assert.equal(failResult.ok, false, '登录失败时 ok 必须为 false');
assert.ok(failResult.error, '登录失败必须回传 error 供排查');
assert.equal(failResult.state.hasToken, false, '登录失败必须保持未登录态');
wxLoginBehavior = 'ok';

// 4. 超时兜底：wx.login 永不回调时必须在超时后放行
auth.clearSession();
guard.__resetForTest();
wx.login = () => {
  /* 故意不回调，模拟卡死 */
};
const start = Date.now();
const timeoutResult = await entry.ensureEntryLogin({ timeout: 120 });
const elapsed = Date.now() - start;
assert.equal(timeoutResult.ok, false, '超时必须放行且 ok 为 false');
assert.equal(timeoutResult.timedOut, true, '超时必须标记 timedOut');
assert.ok(elapsed >= 120 && elapsed < 1500, `超时兜底必须在约定时间附近返回，实际 ${elapsed}ms`);

// 5. 引导去重：首次可引导，标记后会话内与冷却期内不再引导
storage.clear();
entry.__resetForTest();
assert.equal(entry.shouldPromptEntry(1000), true, '未引导过时必须引导');
entry.markEntryPrompted(1000);
assert.equal(entry.shouldPromptEntry(1000 + entry.ENTRY_PROMPT_COOLDOWN - 1), false, '冷却期内不得重复引导');
entry.__resetForTest();
assert.equal(
  entry.shouldPromptEntry(1000 + entry.ENTRY_PROMPT_COOLDOWN + 1),
  true,
  '冷却期结束后可再次引导'
);

// 6. 开关关闭时永不引导（合规兜底）
entry.setPromptEnabled(false);
storage.clear();
entry.__resetForTest();
assert.equal(entry.shouldPromptEntry(9000), false, '关闭引导开关后不得引导');
entry.setPromptEnabled(true);

// 7. 入口还原：只认白名单，其余回首页
assert.equal(entry.resolveEntryTarget(), '/pages/home/home', '无参数时必须回首页');
assert.equal(
  entry.resolveEntryTarget({ from: 'pages/coupon-stores/coupon-stores', query: 'couponId=9' }),
  '/pages/coupon-stores/coupon-stores?couponId=9',
  '白名单来源必须还原页面与安全参数'
);
assert.equal(
  entry.resolveEntryTarget({ from: 'pages/role-withdraw/role-withdraw', query: 'amount=999' }),
  '/pages/home/home',
  '非白名单来源必须回首页，防止被构造参数跳转'
);
assert.equal(
  entry.resolveEntryTarget({ from: 'pages/coupon-stores/coupon-stores', query: 'evil=1' }),
  '/pages/coupon-stores/coupon-stores',
  '白名单外的 query 参数必须被丢弃'
);
assert.equal(
  entry.buildLaunchQuery({ from: 'pages/coupon-stores/coupon-stores', query: 'couponId=9' }),
  'from=pages%2Fcoupon-stores%2Fcoupon-stores&query=couponId%3D9',
  '跳启动页的上下文必须编码'
);

console.log('入口静默登录、超时兜底与引导去重测试通过');
```

运行确认失败：

```powershell
cd user-h5; node scripts/entry-login.test.mjs
```
Expected: FAIL — `Cannot find module 'utils/entry-login.js'`

- [x] **Step 2: 实现 `utils/entry-login.js`**

```js
const guard = require('./login-guard');
const auth = require('./auth');
const authState = require('./auth-state');
const { refreshUserProfileFromRemote } = require('./user-profile');

/**
 * 入口层：让用户「进入小程序即完成微信登录」。
 *
 * 定位：在既有三层（静默 / 登录 / 手机号）之上补一层「入口编排」，
 * 只负责两件事：
 *   1) 冷启动时把静默登录跑完，并带超时兜底（失败也必须放行，不能卡启动页）；
 *   2) 决定是否弹出「绑手机号」引导，并保证同一会话 + 冷却期内只引导一次。
 *
 * 合规边界：getPhoneNumber 只能由用户点击触发，本模块绝不程序化调起授权，
 * 只做跳转到授权页的编排；授权页保留「暂不登录」。
 */

const ENTRY_PROMPT_KEY = 'milkTea:auth:entry-prompted-at';
// 引导冷却期：12 小时内不重复打扰（用户明确拒绝过就更不该反复弹）
const ENTRY_PROMPT_COOLDOWN = 12 * 60 * 60 * 1000;
// 静默登录超时：超过即放行，宁可未登录也不能卡住启动
const ENTRY_LOGIN_TIMEOUT = 1500;
const HOME_PATH = '/pages/home/home';

// 允许从入口直接还原到具体页的来源（其余一律回首页，避免任意跳转）
// key 为 app.json 里的完整页面路径，value 为该页允许透传的参数白名单。
const ENTRY_TARGET_WHITELIST = {
  'pages/coupon-stores/coupon-stores': ['couponId'],
  'pages/coupon-products/coupon-products': ['couponId', 'storeId'],
  'pages/points-exchange/points-exchange': ['id'],
  'pages/gift-card-purchase/gift-card-purchase': ['id']
};

let promptEnabled = true;
let promptedThisSession = false;
let promptTrigger = 'launch';

function readStorage(key) {
  try {
    return typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(key) : '';
  } catch (error) {
    return '';
  }
}

function writeStorage(key, value) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(key, value);
  } catch (error) {
    // 存储失败不影响主流程
  }
}

/** 是否允许引导（合规总开关，测试与灰度可关） */
function setPromptEnabled(enabled) {
  promptEnabled = Boolean(enabled);
}

function isPromptEnabled() {
  return promptEnabled;
}

/** 测试用：清空会话标记与内存态（不删冷却时间戳，由 storage.clear 控制） */
function __resetForTest() {
  promptedThisSession = false;
  promptTrigger = 'launch';
}

/**
 * 本次是否应该弹绑手机号引导。
 * 三重去重：开关关闭 / 本次会话已引导 / 冷却期内已引导。
 */
function shouldPromptEntry(now = Date.now()) {
  if (!promptEnabled) return false;
  if (promptedThisSession) return false;
  const last = Number(readStorage(ENTRY_PROMPT_KEY)) || 0;
  if (last > 0 && now - last < ENTRY_PROMPT_COOLDOWN) return false;
  return true;
}

/** 标记已引导（写入冷却时间戳 + 会话标记） */
function markEntryPrompted(now = Date.now()) {
  promptedThisSession = true;
  writeStorage(ENTRY_PROMPT_KEY, now);
}

/**
 * 入口静默登录：跑完 wx.login 换 token，并保证在 timeout 内返回。
 * 永不 reject —— 调用方（启动页）必须能无条件继续跳转。
 */
function ensureEntryLogin(options = {}) {
  const timeout = Number(options.timeout) > 0 ? Number(options.timeout) : ENTRY_LOGIN_TIMEOUT;

  return new Promise(resolve => {
    let settled = false;
    const finish = payload => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(payload);
    };
    const timer = setTimeout(() => {
      finish({ ok: false, state: authState.getAuthState(), timedOut: true });
    }, timeout);

    guard
      .ensureSilentLogin()
      .then(() => refreshUserProfileFromRemote())
      .then(() => finish({ ok: true, state: authState.getAuthState(), timedOut: false }))
      .catch(error => finish({ ok: false, state: authState.getAuthState(), timedOut: false, error }));
  });
}

/**
 * 还原入口目标页。
 * 只认白名单内的来源页面，其余（含未识别的 scene）一律回首页，
 * 避免被构造参数跳到任意页面。
 */
function resolveEntryTarget(options = {}) {
  const opts = options || {};
  const from = opts.from ? decodeURIComponent(String(opts.from)) : '';
  const allowed = ENTRY_TARGET_WHITELIST[from];
  if (!allowed) return HOME_PATH;

  const rawQuery = opts.query ? decodeURIComponent(String(opts.query)) : '';
  if (!rawQuery) return `/${from}`;
  const params = rawQuery
    .split('&')
    .map(pair => pair.split('='))
    .filter(pair => allowed.indexOf(pair[0]) !== -1 && pair[1])
    .map(pair => `${pair[0]}=${pair[1]}`);
  return params.length ? `/${from}?${params.join('&')}` : `/${from}`;
}

/** 页面跳启动页时携带上下文用 */
function buildLaunchQuery(options = {}) {
  const opts = options || {};
  const parts = [];
  if (opts.from) parts.push(`from=${encodeURIComponent(String(opts.from))}`);
  if (opts.query) parts.push(`query=${encodeURIComponent(String(opts.query))}`);
  return parts.join('&');
}

module.exports = {
  ENTRY_PROMPT_KEY,
  ENTRY_PROMPT_COOLDOWN,
  ENTRY_LOGIN_TIMEOUT,
  HOME_PATH,
  ENTRY_TARGET_WHITELIST,
  setPromptEnabled,
  isPromptEnabled,
  __resetForTest,
  shouldPromptEntry,
  markEntryPrompted,
  ensureEntryLogin,
  resolveEntryTarget,
  buildLaunchQuery
};
```

- [x] **Step 3: 运行测试确认通过**

```powershell
cd user-h5; node scripts/entry-login.test.mjs
```
Expected: PASS — `入口静默登录、超时兜底与引导去重测试通过`

- [x] **Step 4: 接入 `npm run check`**

`user-h5/package.json` 的 `check` 链在 `check-project.mjs` 之前插入：

```
&& node scripts/entry-login.test.mjs
```

并新增独立脚本：

```json
"test:entry": "node scripts/entry-login.test.mjs"
```

- [x] **Step 5: 提交**

```bash
git add user-h5/utils/entry-login.js user-h5/scripts/entry-login.test.mjs user-h5/package.json
git commit -m "feat(user-h5): 新增入口静默登录与引导去重工具"
```

---

### Task 2: 启动页 `pages/launch/launch`

**Files:**
- Create: `user-h5/pages/launch/launch.js`、`.json`、`.wxml`、`.wxss`
- Modify: `user-h5/app.json`、`user-h5/utils/share.js`、`user-h5/scripts/check-project.mjs`

**Interfaces:**
- Consumes: Task 1 的 `ensureEntryLogin()`、`shouldPromptEntry()`、`resolveEntryTarget()`。
- Produces: 路由 `pages/launch/launch`，query 形如 `?from=<encodeURIComponent(route)>&query=<encodeURIComponent(k=v&k2=v2)>`。

- [x] **Step 1: 注册页面与入口页**

`user-h5/app.json`：`pages` 数组**首位**插入 `"pages/launch/launch"`（原首页保持第二个），并在 `window` 同级新增：

```json
"entryPagePath": "pages/launch/launch",
```

`user-h5/scripts/check-project.mjs` 的 `expectedPages` 首位同步插入 `'pages/launch/launch'`。

- [x] **Step 2: 写启动页四件套**

`pages/launch/launch.json`：

```json
{
  "navigationStyle": "custom",
  "disableScroll": true,
  "usingComponents": {}
}
```

`pages/launch/launch.wxml`：

```xml
<view class="launch-page">
  <view class="launch-brand">
    <view class="launch-brand__mark">
      <image class="launch-brand__mark-icon" src="/assets/icons/lucide/leaf-white.svg" mode="aspectFit" />
    </view>
    <text class="launch-brand__name">五零时光</text>
    <text class="launch-brand__slogan">新中式养生茶饮</text>
  </view>
  <view class="launch-loading">
    <view class="launch-loading__dot"></view>
    <view class="launch-loading__dot"></view>
    <view class="launch-loading__dot"></view>
  </view>
</view>
```

`pages/launch/launch.js`：

```js
const { withShare } = require('../../utils/share');
const entry = require('../../utils/entry-login');

/**
 * 启动页：所有冷启动入口（扫码 / 分享卡片 / 朋友圈 / 图标）的统一收口。
 *
 * 流程：
 *   1) 静默登录（带超时兜底，失败也放行）；
 *   2) 需要引导绑手机号 -> reLaunch 到授权页（带 mode=entry 与来源参数）；
 *   3) 否则 reLaunch 到还原后的目标页。
 *
 * 全程只做路由编排，不做业务；任何异常都必须能落到目标页，绝不卡死。
 *
 * 说明：本页已登记为私密页（utils/share.js 的 PRIVATE_PAGES），分享一律回落首页，
 * 但按项目约定仍统一套 withShare 包装器 —— scripts/share.test.mjs 会逐页断言
 * app.json.pages 中的每个页面都使用 withShare。
 * 合规约束：本页绝不程序化调起 getPhoneNumber，只做跳转编排。
 */
Page(
  withShare({
    onLoad(options) {
      this.entryOptions = options || {};
      this.route();
    },
    route() {
      const options = this.entryOptions || {};
      entry.ensureEntryLogin().then(result => {
        const state = result && result.state ? result.state : { level: 'anonymous' };
        const target = entry.resolveEntryTarget(options);
        // 仅登录成功但未绑手机号时才引导；引导开关与去重由工具统一判断
        const needPrompt = state.level !== 'full' && entry.shouldPromptEntry();
        if (!needPrompt) {
          this.goTarget(target);
          return;
        }
        entry.markEntryPrompted();
        const params = ['mode=entry', `target=${encodeURIComponent(target)}`];
        if (options.from) params.push(`from=${encodeURIComponent(String(options.from))}`);
        if (options.query) params.push(`query=${encodeURIComponent(String(options.query))}`);
        wx.reLaunch({
          url: `/pages/auth-login/auth-login?${params.join('&')}`,
          // 引导页跳转失败时不能停在启动页
          fail: () => this.goTarget(target)
        });
      });
    },
    goTarget(target) {
      wx.reLaunch({
        url: target || entry.HOME_PATH,
        fail() {
          // 目标页非法或栈异常时回落首页，避免停在启动页
          wx.reLaunch({ url: entry.HOME_PATH });
        }
      });
    }
  })
);
```

`pages/launch/launch.wxss`（只用设计 token，rpx）：

```css
.launch-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100vh;
  background: var(--card-bg);
}

.launch-brand {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.launch-brand__mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 128rpx;
  height: 128rpx;
  background: var(--brand-green);
  border-radius: var(--radius-lg);
}

.launch-brand__mark-icon {
  width: 72rpx;
  height: 72rpx;
}

.launch-brand__name {
  margin-top: 24rpx;
  color: var(--text-main);
  font-size: var(--font-display);
  font-weight: 600;
  line-height: 64rpx;
}

.launch-brand__slogan {
  margin-top: 8rpx;
  color: var(--text-muted);
  font-size: var(--font-sm);
  line-height: 36rpx;
}

.launch-loading {
  display: flex;
  flex-direction: row;
  margin-top: 40rpx;
}

.launch-loading__dot {
  width: 12rpx;
  height: 12rpx;
  margin: 0 8rpx;
  background: var(--brand-green);
  border-radius: var(--radius-pill);
  opacity: 0.4;
}
```

- [x] **Step 3: 启动页列入私密页**

`user-h5/utils/share.js` 的 `PRIVATE_PAGES` 加入 `'pages/launch/launch'`（启动页不可分享、不可被朋友圈直达）。

- [x] **Step 4: 运行结构校验**

```powershell
cd user-h5; node scripts/check-project.mjs
```
Expected: PASS — `项目结构校验通过: 51 个页面、5 个 Tab`

- [x] **Step 5: 提交**

```bash
git add user-h5/pages/launch user-h5/app.json user-h5/utils/share.js user-h5/scripts/check-project.mjs
git commit -m "feat(user-h5): 新增启动页统一冷启动登录入口"
```

---

### Task 3: `app.js` 接入入口登录

**Files:**
- Modify: `user-h5/app.js`

**Interfaces:**
- Consumes: Task 1 的 `ensureEntryLogin()`。
- Produces: `globalData.loginFailed / loginError` 语义不变（`profile` 页依赖）。

- [x] **Step 1: 替换 onLaunch 的静默登录段**

将 `app.js` 中 `loginGuard.ensureSilentLogin().then(...).then(...).catch(...)` 整段替换为：

```js
    // 入口静默登录：wx.login 换 token，用户无感；带超时兜底，失败不阻塞启动。
    // 失败时记录 loginFailed 供页面感知（避免「静默 401 一片红」而无任何提示），
    // 真正需要登录的操作仍由 loginGuard 引导授权。
    entryLogin
      .ensureEntryLogin()
      .then(result => {
        this.globalData.loginFailed = !result.ok;
        this.globalData.loginError = result.ok ? '' : (result.error && result.error.message) || '登录失败';
        // 资料拉取已在 ensureEntryLogin 内部完成，此处直接同步登录态
        this.syncAuthState();
        if (!result.ok && !result.timedOut) {
          console.warn('[login] 静默登录失败：', this.globalData.loginError);
        }
      });
```

并在文件顶部 `require` 区新增：

```js
const entryLogin = require('./utils/entry-login');
```

同时删除仅服务于旧写法的 `loginGuard` 引入（若无其他引用）。

- [x] **Step 2: 语法与结构校验**

```powershell
cd user-h5; node --check app.js; node scripts/check-project.mjs; node scripts/entry-login.test.mjs
```
Expected: 三条命令均无输出/通过

- [x] **Step 3: 提交**

```bash
git add user-h5/app.js
git commit -m "refactor(user-h5): 入口静默登录改由 entry-login 统一编排"
```

---

### Task 4: 授权页 `mode=entry` 引导态

**Files:**
- Modify: `user-h5/pages/auth-login/auth-login.js`、`auth-login.wxml`
- Test: `user-h5/scripts/role-function-pages.test.mjs`

**Interfaces:**
- Consumes: 启动页传入的 `mode=entry&target=<encoded>&from=<encoded>&query=<encoded>`。
- Produces: 授权成功后 `reLaunch` 到 `target`（而非 `navigateBack`），保证页面栈干净。

- [x] **Step 1: 写失败测试**

在 `scripts/role-function-pages.test.mjs` 的授权页断言段（`authJs.includes('guard.flushPendingAction()')` 之后）追加：

```js
// 启动页引导态：进入即登录的入口编排
assert.ok(
  authJs.includes("isEntryMode") && authJs.includes("mode === 'entry'"),
  'auth page must detect entry mode'
);
assert.ok(
  authJs.includes("wx.reLaunch") && authJs.includes('entryTarget'),
  'entry mode must reLaunch to the resolved target instead of navigateBack'
);
assert.ok(
  authWxml.includes('entryTip'),
  'entry mode must show the lighter entry prompt copy'
);
assert.ok(
  fs.existsSync(path.join(root, 'pages/launch/launch.js')) &&
    fs.existsSync(path.join(root, 'pages/launch/launch.wxml')),
  'launch page must exist as the single cold-start entry'
);
assert.ok(
  appJson.entryPagePath === 'pages/launch/launch',
  'app.json must set entryPagePath to the launch page'
);
assert.ok(
  appJson.pages[0] === 'pages/launch/launch',
  'launch page must be the first registered page'
);
const launchJs = fs.readFileSync(path.join(root, 'pages/launch/launch.js'), 'utf8');
assert.ok(
  launchJs.includes('ensureEntryLogin') && launchJs.includes('shouldPromptEntry'),
  'launch page must gate on entry login and prompt dedupe'
);
assert.ok(
  launchJs.includes('markEntryPrompted'),
  'launch page must mark the entry prompt to avoid repeat interruption'
);
assert.ok(
  !launchJs.includes('getPhoneNumber'),
  'launch page must never trigger phone authorization programmatically'
);
```

运行确认失败：

```powershell
cd user-h5; node scripts/role-function-pages.test.mjs
```
Expected: FAIL — `auth page must detect entry mode`

- [x] **Step 2: 实现引导态**

`auth-login.js` 的 `data` 增加：

```js
      entryMode: false,
      entryTip: '登录后可下单、领券并同步会员权益',
      entryTarget: '',
```

`onLoad` 内解析：

```js
      const isEntryMode = (opts.mode || '') === 'entry';
      this.entryTarget = opts.target ? decodeURIComponent(opts.target) : '/pages/home/home';
      this.setData({
        reason: this.reason,
        entryMode: isEntryMode,
        entryTip: isEntryMode ? '登录后可下单、领券并同步会员权益' : '',
        entryTarget: this.entryTarget
      });
```

`afterBound()` 改为按模式分流（保留原有 `flushPendingAction` 语义，测试契约不变）：

```js
    afterBound() {
      this.completed = true;
      this.stopCountdown();
      guard.toast('登录成功');
      // 入口引导态：走 reLaunch 到目标页，避免启动页残留在页面栈
      if (this.data.entryMode) {
        wx.reLaunch({
          url: this.entryTarget || '/pages/home/home',
          complete() {
            guard.flushPendingAction();
          }
        });
        return;
      }
      // 交易拦截态：必须等返回来源页再续跑原操作：
      // navigateBack 是异步的，若立即 flush，原操作可能在授权页上下文中执行。
      wx.navigateBack({
        delta: 1,
        complete() {
          // complete 覆盖成功与失败（栈底无法返回时也要保证续跑不丢）
          guard.flushPendingAction();
        }
      });
    },
```

`handleSkip()` 同样按模式分流：

```js
    handleSkip() {
      this.completed = true;
      guard.clearPendingAction();
      if (this.data.entryMode) {
        wx.reLaunch({ url: this.entryTarget || '/pages/home/home' });
        return;
      }
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.reLaunch({ url: '/pages/home/home' });
    },
```

`auth-login.wxml` 在 `<view class="auth-brand">` 之后插入引导提示（复用既有 `auth-reason` 样式，不新增颜色）：

```xml
      <view wx:if="{{entryMode}}" class="auth-reason">
        <image class="auth-reason__icon" src="/assets/icons/lucide/leaf-white.svg" mode="aspectFit" />
        <text class="auth-reason__text">{{entryTip}}</text>
      </view>
```

> 注意：`auth-reason` 现有规则背景为 `var(--brand-soft)`，`leaf-white.svg` 在其中对比度不足。改为复用已有的 `circle-help.svg`（该图标当前色值适配浅底），保持一致：

```xml
        <image class="auth-reason__icon" src="/assets/icons/lucide/circle-help.svg" mode="aspectFit" />
```

- [x] **Step 3: 运行测试确认通过**

```powershell
cd user-h5; node scripts/role-function-pages.test.mjs; node scripts/profile-data-page.test.mjs
```
Expected: 两条均 PASS（后者校验 `navigateBack + complete + flushPendingAction` 契约仍存在）

- [x] **Step 4: 提交**

```bash
git add user-h5/pages/auth-login user-h5/scripts/role-function-pages.test.mjs
git commit -m "feat(user-h5): 授权页支持进入即登录的引导态"
```

---

### Task 5: 我的页与首页的二级引导

**Files:**
- Modify: `user-h5/pages/profile/profile.js`、`profile.wxml`
- Modify: `user-h5/pages/home/home.wxml`

**Interfaces:**
- Consumes: 现有 `handleBindPhone()`（`loginGuard.requirePhone`）、`authStateLevel`。
- Produces: 无新接口。

- [x] **Step 1: 我的页引导条**

`profile.wxml` 在 `profile-content` 内 `user-card` 之后插入：

```xml
      <view
        wx:if="{{authStateLevel === 'authorized'}}"
        class="bind-phone-bar"
        bindtap="handleBindPhone"
        aria-role="button"
        aria-label="绑定手机号以便下单与领取优惠券"
      >
        <image class="bind-phone-bar__icon" src="/assets/icons/lucide/phone.svg" mode="aspectFit" />
        <text class="bind-phone-bar__text">绑定手机号，下单与领券更顺畅</text>
        <image class="bind-phone-bar__arrow" src="/assets/icons/lucide/chevron-right.svg" mode="aspectFit" />
      </view>
```

`profile.wxss` 追加（沿用既有 `profile-card` 视觉，token + rpx）：

```css
.bind-phone-bar {
  display: flex;
  align-items: center;
  margin: 16rpx 20rpx 0;
  padding: 20rpx 24rpx;
  background: var(--brand-soft);
  border-radius: var(--radius-md);
}

.bind-phone-bar__icon {
  flex: none;
  width: 32rpx;
  height: 32rpx;
  margin-right: 12rpx;
}

.bind-phone-bar__text {
  flex: 1;
  min-width: 0;
  color: var(--text-main);
  font-size: var(--font-sm);
  line-height: 36rpx;
}

.bind-phone-bar__arrow {
  flex: none;
  width: 24rpx;
  height: 24rpx;
  margin-left: 8rpx;
}
```

- [x] **Step 2: 首页昵称位登录引导**

`home.wxml` 的 `user-strip` 内 `<text>{{userProfile.nickname}}</text>` 改为：

```xml
      <text wx:if="{{userProfile.nickname}}">{{userProfile.nickname}}</text>
      <text
        wx:else
        class="user-strip__login"
        bindtap="handleLoginTap"
        aria-role="button"
        aria-label="登录后可同步会员权益"
      >点击登录</text>
```

`home.js` 增加方法（复用 `requireLogin`，授权完成后自动续跑无副作用动作）：

```js
    /** 首页昵称位登录入口：引导登录（不阻塞浏览） */
    handleLoginTap() {
      loginGuard.requireLogin(null, { reason: '登录后可同步会员权益与订单' });
    },
```

并在 `home.js` 顶部 require `loginGuard`。`home.wxss` 追加 `.user-strip__login { color: var(--brand-green); }`。

- [x] **Step 3: 校验**

```powershell
cd user-h5; node scripts/check-project.mjs; node scripts/acceptance-check.test.mjs
```
Expected: 均通过

- [x] **Step 4: 提交**

```bash
git add user-h5/pages/profile user-h5/pages/home
git commit -m "feat(user-h5): 我的页与首页补充绑手机号引导入口"
```

---

### Task 6: 全量回归与文档

**Files:**
- Modify: `docs/login-authorization.md`

- [x] **Step 1: 全量校验**

```powershell
cd user-h5; npm run check; npm run test:acceptance
```
Expected: `npm run check` 全部脚本通过（含新增 `entry-login.test.mjs`），`项目结构校验通过: 51 个页面、5 个 Tab`；`test:acceptance` 输出 `高清首页、点单页与素材验收测试通过`

- [x] **Step 2: 文档补充**

`docs/login-authorization.md` 追加「八、进入即登录（入口层）」章节，写明：三层 + 入口层的职责边界、`entryPagePath` 收口原理、引导去重规则（会话 + 12h 冷却）、合规说明（静默登录可强制、手机号只能引导）、验证命令。

- [x] **Step 3: 提交**

```bash
git add user-h5 docs/login-authorization.md
git commit -m "test(user-h5): 进入即登录全量回归与文档更新"
```

---

## 六、验收标准

| 编号 | 场景 | 期望 |
|------|------|------|
| A1 | 冷启动（图标进） | 启动页 → 无感拿到 token → 授权页引导一次 → reLaunch 首页 |
| A2 | 扫码 / 分享卡片进 | 同上，且白名单来源可还原到对应页 |
| A3 | 冷启动时后端不可用 | 1.5s 内放行，正常进入首页，我的页显示「登录失败，点击重试」 |
| A4 | 授权页点「暂不登录」 | 正常进入首页，本次会话不再弹引导 |
| A5 | 再次冷启动（12h 内） | 不再弹引导，仅静默登录 |
| A6 | 已绑手机号用户冷启动 | 不弹引导，直接进首页 |
| A7 | 首页显示 | 昵称位显示「点击登录」，点击可拉起授权页 |
| A8 | 我的页 | `authorized` 态显示绑手机号引导条，点击可拉起授权页 |
| A9 | 交易拦截（下单 / 兑换 / 提现） | 行为与改造前完全一致，授权后自动续跑原操作 |
| A10 | 合规 | 启动页与 `app.js` 均无 `getPhoneNumber` 程序化调用；授权页保留「暂不登录」 |

**验证命令（缺一不可）**

```powershell
cd user-h5
node scripts/entry-login.test.mjs
node scripts/role-function-pages.test.mjs
node scripts/profile-data-page.test.mjs
node scripts/check-project.mjs
npm run check
npm run test:acceptance
```

## 七、风险与回滚

| 风险 | 影响 | 应对 |
|------|------|------|
| `entryPagePath` 让冷启动多一跳 | 首页首屏慢约 1 帧 | 启动页纯静态无接口，登录最多等 1.5s；超时立即放行 |
| 静默登录失败卡启动页 | 用户无法进入 | `ensureEntryLogin` 永不 reject + 硬超时；`goTarget` fail 回落首页 |
| 引导被用户视为打扰 | 体验下降 | 只引导一次 + 12h 冷却 + 可跳过；`setPromptEnabled(false)` 可一键关闭 |
| 审核判定诱导授权 | 版本被驳回 | 严格不强制，保留「暂不登录」，不做阻断式授权 |
| 页面栈异常 | reLaunch 失败白屏 | 全部跳转走 `reLaunch` + `fail` 回落首页 |

**回滚**：`app.json` 删除 `entryPagePath` 字段即可立刻退回「无启动页」行为，其余新增文件不生效、无副作用。

## 八、明确不做的事

- ❌ 不做「不绑手机号不能用」（审核风险）；
- ❌ 不在 `app.js` / 页面中程序化调 `getPhoneNumber`（技术上不可能，且违规）；
- ❌ 不改后端接口、不动 `security-common` 鉴权范围（当前已满足）；
- ❌ 不引入第三方 UI 库、不写死色值、不新增非 Lucide 图标。





