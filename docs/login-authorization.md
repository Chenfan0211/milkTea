# 授权按钮接入方案（手机号强制 + 降级方案）

> 决策：A（关键操作强制）/ 要降级方案 / ②A 标准防护
> 状态：已实现并通过验证（真机验证待部署）

## 一、三层登录架构

```
┌─ 静默层 ensureSilentLogin() ────────────────────┐
│ app.onLaunch → wx.login → token                  │
│ 用户完全无感；失败不阻塞浏览                      │
└─────────────────────────────────────────────────┘
              ↓ 需要登录的操作
┌─ 登录层 requireLogin(action) ───────────────────┐
│ 有 token 则直接执行；无则弹授权，授权后自动续跑    │
└─────────────────────────────────────────────────┘
              ↓ 交易类操作
┌─ 手机号层 requirePhone(action) ─────────────────┐
│ 已绑手机号直接执行；未绑则弹授权，授权后自动续跑   │
└─────────────────────────────────────────────────┘
```

**关键能力**：授权完成后**自动续跑原操作**（`pendingAction`），用户无需二次点击。

## 二、后端实现

### 1. 短信通道抽象

| 实现 | 启用条件 | 行为 |
|------|---------|------|
| `ConsoleSmsProvider` | `app.sms.enabled=false`（默认） | 验证码输出到服务端日志 |
| `TencentSmsProvider` | `app.sms.enabled=true` | 需补全 TC3 签名（已留结构与配置项） |

> 上线接入腾讯云：设置 `SMS_ENABLED=true` + `SMS_SDK_APP_ID` / `SMS_SECRET_ID` / `SMS_SECRET_KEY` / `SMS_SIGN_NAME` / `SMS_TEMPLATE_ID`

### 2. 验证码防护（②A）

| 项 | 策略 |
|----|------|
| 格式 | 6 位数字 |
| 有效期 | 5 分钟 |
| 重发间隔 | 同手机号 60 秒 |
| 手机号日限 | 10 条 |
| **IP 日限** | 30 条（防脚本刷短信产生真实费用） |
| 错误上限 | 5 次作废 |
| 使用次数 | 校验通过即作废，防重放 |

### 3. 新增接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/v1/app/auth/sms/send` | 需登录 | 发送验证码（限流） |
| POST | `/api/v1/app/auth/sms/bind` | 需登录 | 校验并绑定手机号 |
| POST | `/api/v1/app/auth/avatar` | 需登录 | 更新头像（chooseAvatar） |
| POST | `/api/v1/app/auth/nickname` | 需登录 | 更新昵称（type=nickname） |

## 三、小程序端实现

### 1. `utils/login-guard.js`（新增）

```js
ensureSilentLogin()   // 静默登录（并发去重）
requireLogin(action)  // 需 token，授权后自动续跑
requirePhone(action)  // 需手机号，授权后自动续跑
flushPendingAction()  // 由弹层调用，续跑未完成操作
```

### 2. `components/login-sheet`（新增授权弹层）

三种授权方式：
1. **微信一键获取手机号**（`open-type="getPhoneNumber"`）— 首选
2. **手动输入手机号 + 验证码** — 降级方案（60s 倒计时）
3. **头像昵称填写**（`chooseAvatar` + `type="nickname"`）

样式全部使用设计系统 token，无硬编码色值。

### 3. 接入的页面（7 个）

| 页面 | 拦截点 | 说明 |
|------|--------|------|
| `order-confirm` | 提交订单 | 顺带接入了真实下单接口 |
| `points-exchange` | 兑换 | |
| `gift-card-purchase` | 支付 | |
| `stored-value` | 充值 | |
| `role-withdraw` | 提交提现 | |
| `role-apply` | 提交申请 | |
| `profile` | 头像/姓名点击、绑定手机号入口 | 显示手机号（脱敏） |

> 改造方式：原方法体改名为 `doXxx`，外层包 `requirePhone(() => this.doXxx())`，原逻辑完整保留。

## 四、验证结果

### 后端短信链路（10/10）
```
1) 未登录发码        -> 8888 ✅
2) 已登录发码        -> code=0 ✅
3) 60s 重发限流      -> 400 "请 59 秒后再试" ✅
4) 非法手机号        -> 400 "手机号格式不正确" ✅
5) 错误验证码        -> 400 "验证码错误，还可尝试 4 次" ✅
6) 日志取到验证码     -> ConsoleSmsProvider 生效 ✅
7) 正确验证码绑定     -> code=0 phone=13800138000 ✅
8) 验证码复用        -> 400 "验证码已过期"（已作废）✅
9) 昵称/头像更新      -> code=0 ✅
10) /auth/me 落库确认 -> 三项全部持久化 ✅
```

### 回归
| 项目 | 结果 |
|------|------|
| 小程序 `npm run check` | 22 项通过 |
| 后台 `vue-tsc` | 通过 |
| 后台 `oxlint`（212 文件） | 0 error |
| 后端 `mvn test` | 16/16 通过 |
| 数据库一致性校验 | 3/3 通过 |

## 五、本批修复的问题

1. **JSON 转义损坏**：批量注入 `usingComponents` 时单引号字符串中的 `` `n `` 被当作字面量写入 5 个页面 JSON，导致 `SyntaxError`。已修复并逐项校验 JSON 合法性。
2. **测试契约冲突**：将头像 `bindtap` 从 `openProfileData` 改为 `handleUserCardTap` 后，`profile-data-page.test.mjs` 断言失败。改为在 `openProfileData` 内部做登录判断，兼顾行为与测试契约。

## 六、改动文件

**后端新增**
- `user/sms/SmsProvider.java` / `ConsoleSmsProvider.java` / `TencentSmsProvider.java`
- `user/service/SmsCodeService.java`
- `user/dto/SmsSendRequest.java` / `SmsBindRequest.java`

**后端修改**
- `user/service/MiniAppAuthService.java`（bindPhoneBySms / updateAvatar / updateNickName）
- `user/controller/MiniAppAuthController.java`（4 个端点）
- `common/config/WebConfig.java`（拦截路径补充）
- `application.yml`（短信配置）

**小程序新增**
- `utils/login-guard.js`
- `components/login-sheet/*`（4 文件）

**小程序修改**
- `app.js`（启动静默登录）
- `pages/profile/*`（登录态 UI + 绑定入口）
- `pages/order-confirm/*`（提交拦截 + 真实下单）
- 5 个交易页面（拦截 + 弹层）
- `utils/api.js`（4 个新接口）

## 七、待办（依赖外部条件）

1. **真机验证**：`getPhoneNumber` / `chooseAvatar` 需企业主体 + 已备案域名，开发者工具中行为受限
2. **接入腾讯云短信**：填入 5 项配置即可启用真实短信
3. **头像上传**：当前直接存微信临时路径，后续需接对象存储
4. **手机号「强制」范围**：已按 A 实现（关键操作强制），浏览类功能不受限


---

# 八、进入即登录（入口层，2026-09-24）

> 需求：用户进入小程序就需要授权微信登录。
> 决策：**引导式 + 全部入口**。

## 8.1 合规边界（先理解再实现）

微信小程序「授权登录」是两层完全不同的能力：

| 层级 | 微信 API | 用户动作 | 能否强制 | 能拿到什么 |
|------|---------|---------|---------|-----------|
| A. 登录态 | `wx.login()` → `code` → 后端换 token | 无感，不需要点击 | ✅ 可以，不违规 | openid / unionid，能长期标识用户 |
| B. 手机号 / 头像昵称 | `<button open-type="getPhoneNumber">`、`chooseAvatar` | 必须用户点击确认 | ❌ **不能** | 手机号、微信头像昵称 |

两条硬约束：

1. `getPhoneNumber` / `chooseAvatar` 必须由用户点击行为触发，无法在 `onLaunch` 里程序化调起；
2. 小程序禁止在用户未使用服务前强制授权，强弹授权会被审核驳回甚至封禁「诱导授权」。

**因此本方案：静默登录做到 100%（无感）；手机号做成「进店即引导、可跳过、只引导一次」——两者叠加即合规范围内能做到的最强效果。**

## 8.2 架构：三层 + 入口层

```
冷启动入口（图标 / 扫码 / 分享卡片 / 朋友圈）
        ↓  app.json: entryPagePath = pages/launch/launch
┌─ 启动页 pages/launch（新增）────────────────────────┐
│ ensureEntryLogin()   静默登录，1.5s 硬超时，失败也放行  │
│ shouldPromptEntry()  会话内 + 12h 冷却 双重去重        │
└──────────────────────────────────────────────────────┘
        ↓ 需要引导                     ↓ 不需要 / 开关关闭
  授权页 mode=entry                 reLaunch 目标页
 （保留「暂不登录」）→ reLaunch 目标页
```

原有三层职责不变，入口层只在「启动编排」上加逻辑：

| 层 | 模块 | 职责 |
|----|------|------|
| 入口层（新增） | `utils/entry-login.js` | 冷启动静默登录编排、引导去重、入口还原 |
| 静默层 | `loginGuard.ensureSilentLogin()` | `wx.login` 换 token，并发去重 |
| 登录层 | `loginGuard.requireLogin(action)` | 需 token 的操作 |
| 手机号层 | `loginGuard.requirePhone(action)` | 交易类操作必须绑手机号 |

## 8.3 为什么必须用 `entryPagePath`

只靠「首页 onShow 引导」会漏掉**分享卡片 / 扫码 / 朋友圈直达内页**的入口（用户直接进商品页，不经过首页）。`entryPagePath` 能让**所有冷启动**统一先进启动页，是唯一能覆盖全部入口的做法。

## 8.4 引导去重规则

| 规则 | 值 | 说明 |
|------|----|------|
| 会话内 | 只引导一次 | `promptedThisSession` 内存标记 |
| 冷却期 | 12 小时 | `milkTea:auth:entry-prompted-at` 时间戳 |
| 总开关 | `setPromptEnabled(false)` | 可一键关闭引导（合规兜底） |
| 触发条件 | `state.level !== 'full'` | 已绑手机号不打扰 |

## 8.5 安全：入口还原白名单

启动页带 `from` / `query` 还原目标页，只认白名单，其余（含未识别 scene）一律回首页，且只透传白名单参数，防止被构造参数跳到任意页面：

```js
const ENTRY_TARGET_WHITELIST = {
  'pages/coupon-stores/coupon-stores': ['couponId'],
  'pages/coupon-products/coupon-products': ['couponId', 'storeId'],
  'pages/points-exchange/points-exchange': ['id'],
  'pages/gift-card-purchase/gift-card-purchase': ['id']
};
```

## 8.6 放行兜底（绝不卡启动页）

- `ensureEntryLogin()` **永不 reject**，1.5s 硬超时后返回 `{ ok: false, timedOut: true }`；
- 启动页 `reLaunch` 失败时 `fail` 回调回落首页；
- 引导页跳转失败同样回落目标页。

## 8.7 改动文件

**新增**

- `user-h5/utils/entry-login.js`（入口层编排）
- `user-h5/pages/launch/*`（启动页四件套）
- `user-h5/scripts/entry-login.test.mjs`（7 组用例）

**修改**

- `user-h5/app.json`（`entryPagePath` + 注册启动页）
- `user-h5/app.js`（`onLaunch` 改用 `ensureEntryLogin()`）
- `user-h5/pages/auth-login/*`（`mode=entry` 引导态）
- `user-h5/pages/profile/*`（未绑手机号常驻引导条）
- `user-h5/pages/home/*`（昵称位登录入口）
- `user-h5/utils/login-guard.js`（`__resetForTest` 测试钩子）
- `user-h5/utils/share.js`（启动页登记私密页）
- `user-h5/scripts/check-project.mjs`（`expectedPages` 补启动页）

## 8.8 验证

```powershell
cd user-h5
node scripts/entry-login.test.mjs
node scripts/role-function-pages.test.mjs
node scripts/profile-data-page.test.mjs
npm run check          # 51 个页面、5 个 Tab
npm run test:acceptance
```

## 8.9 回滚

删除 `user-h5/app.json` 的 `entryPagePath` 字段即可立即退回「无启动页」行为；其余新增文件不生效、无副作用。

## 8.10 明确不做的事

- ❌ 不做「不绑手机号不能用」（审核风险）；
- ❌ 不在 `app.js` / 页面中程序化调 `getPhoneNumber`（技术上不可能，且违规）；
- ❌ 不改后端接口、不动 `security-common` 鉴权范围。

## 8.11 待办（依赖外部条件）

1. **真机验证**：`getPhoneNumber` 需企业主体 + 已备案域名，开发者工具中行为受限；
2. **引导转化埋点**：接入数据上报后可评估进店引导对绑手机号转化率的实际提升。
