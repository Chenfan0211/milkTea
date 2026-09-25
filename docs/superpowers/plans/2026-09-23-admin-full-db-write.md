# 后台读写全量走接口（阶段 2）

> 日期：2026-09-23
> 需求：其它页面的读写全部走接口；顺带清理 R_ADMIN 死角色、页面内嵌下拉读本地镜像问题。

## 一、现状诊断（关键：不是"没接"，而是 3 类缺陷）

`store` 的 `add / update / remove / patch` **已内置远端分发**：
命中 `REMOTE_RESOURCES[key]` 就走 `crudCreate/crudUpdate/crudDelete`。
故问题不在"没接"，而在下列缺陷：

### 缺陷 1：写操作"乐观 UI + 静默失败"

```js
function add(key, row, ...) {
  const remote = REMOTE_RESOURCES[key];
  if (remote) {
    crudCreate(remote, row).then(...).catch(e => window.$message?.error(...)); // 失败仅 toast
    return { id: Date.now(), ...row };   // 立即返回「假 id」，页面已渲染
  }
  ...
}
```

- 接口失败时，**界面已插入假数据**（含 `id: Date.now()`），用户以为成功
- `update` / `remove` 同理：乐观更新后失败只 toast 回滚，不阻断
- 与已完成的 `queryRemote`（失败抛错）策略不一致

### 缺陷 2：subjects 字段映射缺失

前端用 `type`（小写 `store`/`channel`/...），后端是 `subjectType`（大写 `STORE`/`CHANNEL`/...）。
读已在 `queryRemote` 归一化；**写**仍会传错字段名与值 → 写库失败或写错。

### 缺陷 3：部分业务动作是纯本地逻辑

| 方法 | 问题 |
|------|------|
| `freezeAccount` / `unfreezeAccount` | 直接改本地 `subjectAccounts` 余额 + 本地记账，**不走接口** |
| `applyWithdraw` / `orderIncome` | 本地资金池增减 |
| `reviewApplication` | 直接改本地 `roleApplications`，未调后端审核接口 |
| `executeVerify` / `reviewComment` / `reviewWithdraw` | 部分走 `patch`，部分本地 |

### 缺陷 4：页面内嵌下拉读本地镜像

表单选项 `() => store.subjects.filter(...)` 等，依赖预加载到 localStorage 的镜像，非实时。

## 二、执行计划

### 步骤 1：store 写操作改为「先接口、后本地」，失败抛错

- `add`：改为 async，先 `crudCreate` 成功后再写本地镜像；失败**抛错**
- `update` / `remove` / `patch`：同样先接口成功再改本地
- 保持返回结构，页面 `onSubmit` 已是 async（上一阶段已支持）

### 步骤 2：subjects 写操作字段映射

- 写前将 `type`（小写）转为 `subjectType`（大写）
- 统一在 store 的写路径做归一化，页面无需关心

### 步骤 3：业务动作接真实接口

按「后端是否已有接口」分类处理：

| 动作 | 后端现状 | 处理 |
|------|---------|------|
| `reviewApplication`（角色开通审核） | 有 `reviewRoleApplication` | 接接口 |
| `reviewComment`（评论审核） | 有 `reviewComment` | 接接口 |
| `executeVerify`（核销） | 有 `executeVerifyApi` | 接接口 |
| `refundOrder`（退款） | 有 `refundOrderApi` | 接接口 |
| `toggleFeature`（功能开关） | 走 `patch` → CRUD | 已可，理顺失败处理 |
| `enableSplitRule`（分账规则） | 走 `patch` | 同上 |
| `freezeAccount` / `unfreezeAccount` | 有 `freezeAccount`/`unfreezeAccount` API | 接接口 |
| `applyWithdraw` / `reviewWithdraw` | 有 `reviewWithdraw` / 提现 CRUD | 接接口 |
| `orderIncome` | 无对应接口 | 保持本地或标注 |

> 原则：**有接口的必须接**；确无接口的明确标注并提示，不静默假装成功。

### 步骤 4：页面内嵌下拉改实时数据源

- 表单/搜索的 `options` 改为调用接口获取（或复用已加载的 store 镜像但明确来源）
- 至少保证：主体类下拉用 `eq_subjectType` 实时查询

### 步骤 5：清理遗留

- 删除 `R_ADMIN` 死角色（前端路由未引用）
- 确认无引用后再删

### 步骤 6：验证

- 逐页实测：增 / 改 / 删 / 业务动作 均落库
- 失败路径：断网或传错参数，确认页面报错且不显示假数据
- `pnpm typecheck`

## 三、风险

| 项 | 说明 |
|----|------|
| 服务中断 | 需重建 server / product-service / marketing-service 等镜像 |
| 行为变更 | 写操作由"立即成功"变为"接口确认后成功"，用户可感知（属预期改进） |
| 数据 | 无 schema 变更；仅接口调用路径调整 |
| 回滚 | 前端备份 + jar 备份 |

## 四、验收标准

1. 所有列表页读：真分页来自数据库（已完成，本阶段保持）
2. 所有写操作：落库成功才提示成功；失败明确报错
3. 业务动作：走对应后端接口
4. 无 R_ADMIN 残留
5. 下拉数据来源明确（实时或预加载，有据可查）
