# 复查遗漏修复方案（阶段 3）

> 日期：2026-09-23
> 需求：复查前几轮改动的遗漏，出方案并修复。
> 决策：绑定动作接后端接口；无接口项补后端接口。

## 一、复查发现的 7 处遗漏

### 【高】遗漏 1：`migrate()` 在接口返回空时回填假数据

`src/store/modules/admin/index.ts` L1617-1619：

```js
if (!Array.isArray(data.dictEntries) || data.dictEntries.length === 0) data.dictEntries = defaults.dictEntries || [];
if (!Array.isArray(data.fundPool) || data.fundPool.length === 0) data.fundPool = defaults.fundPool || [];
if (!Array.isArray(data.subjectAccounts) || data.subjectAccounts.length === 0) data.subjectAccounts = defaults.subjectAccounts || [];
```

**问题**：库返回空时**悄悄塞回前端假数据**，直接违背「选项 A：失败不回退」，
且极难察觉（症状：接口明明空了，页面却有条目）。

**修复**：删除这 3 处 defaults 回填，空就是空。

### 【高】遗漏 2：`saveSignInRule` / `saveReferralConfig` 仍是乐观 UI + 静默失败

与上轮修的 `add/update` 同一病症（先改本地、失败只 toast）。页面调用也未 await。

**修复**：改 async，先接口成功后再改本地，失败抛出。

### 【高】遗漏 3：4 个绑定动作纯本地，但后端已有接口

| 动作 | 现状 | 后端接口 |
|------|------|---------|
| `bindUserRole` | 纯本地 | `bindUserRole` ✅ |
| `unbindUserRole` | 纯本地 | `unbindUserRole` ✅ |
| `bindSubjectUser` | 纯本地 | `bindSubjectUser` ✅ |
| `unbindSubjectUser` | 纯本地 | `unbindSubjectUser` ✅ |

**后果**：门店/渠道/投资人/用户页的绑定操作**刷新即丢**。

**修复**：接后端接口 + 成功后重载镜像。注意 `bindUserRole` 后端签名是
`(userId, roleCode, subjectId)`，而前端 store 的 `(userId, roleType, subjectId, subjectName)` 需做参数映射。

### 【中】遗漏 4：`enableSplitRule` 仍是静默 catch

**修复**：改 async + 失败抛出。

### 【中】遗漏 5：`orderIncome` / `applyWithdraw` 无合适后端接口

- **`orderIncome`（手动入账）**：后端**已有自动分账链路** ——
  `OrderVerifiedEvent` → `OrderVerifiedSplitConsumer` → `LedgerService.executeSplit`。
  即核销后自动入账，**前端手动入账按钮在真实系统里不该存在**。
  **修复方案**：移除前端手动入账，改为提示「入账由核销自动触发」；
  或若确有补录需求，新增管理端补录接口（本期先移除，避免双重入账）。
- **`applyWithdraw`（后台代提现）**：`WithdrawalService.apply()` 用 `CurrentUser`（小程序用户），
  后台调用不合适。**修复方案**：新增管理端接口
  `POST /api/v1/admin/finance/withdrawals/apply`（复用 service 的冻结逻辑，用户取管理员）。

### 【中】遗漏 6：14 处页面业务动作调用未 await

分布在 9 个页面（`bindSubjectUser`、`bindInvestorToStore`、`toggleFeature`、
`applyWithdraw`、`saveSignInRule`、`saveReferralConfig` 等）。

**修复**：补齐 await，含 await 的箭头函数改 async。

### 【低】遗漏 7：`referralConfig` 会塞硬编码默认值

L1625：接口未返回时塞死配置。
**修复**：不再塞假数据，缺省即为空对象。

## 二、执行计划

| 步骤 | 内容 |
|------|------|
| 1 | 删 `migrate()` 的 3 处 defaults 回填 + `referralConfig` 默认值 |
| 2 | `saveSignInRule` / `saveReferralConfig` 改 async 先接口后本地 |
| 3 | 4 个绑定动作接后端接口（含参数映射） |
| 4 | `enableSplitRule` 改 async + 失败抛出 |
| 5 | 后端新增管理端代提现接口；前端 `applyWithdraw` 接上 |
| 6 | `orderIncome` 移除前端手动入账（后端核销自动分账） |
| 7 | 补齐 14 处页面 await |
| 8 | 验证：typecheck + 逐项实测 + 34 页回归 |

## 三、风险

| 项 | 说明 |
|----|------|
| 服务中断 | 步骤 5 需重建 server 镜像 |
| **移除手动入账** | 属行为变更，需确认无人在用（当前为演示逻辑） |
| 绑定关系 | 步骤 3 改真实绑定，操作不可逆（有解绑可回退） |
| 数据单位 | `orderIncome`/`applyWithdraw` 原用「元」，后端用「分」，接入时须换算 |

## 四、验收标准

1. 接口返回空时页面显示空，不再出现假数据
2. 签到规则 / 分享规则：失败明确报错且不落本地
3. 绑定/解绑操作刷新后仍在（真正落库）
4. 后台代提现走接口，能在提现列表看到
5. 无「双重入账」风险
6. `pnpm typecheck` 通过 + 34 页回归通过
