# CI 补强：专用接口字段一致性检查

> 日期：2026-09-25
> 触发：2026-09-25 线上 Bug（经营角色显示英文 STORE / 绑定主体列为空 / 解绑无效）
> 状态：✅ 已实现并验证

---

## 一、为什么要做这件事

原 CI 只有 `FieldWriteConsistencyTest`，它<b>只覆盖走「通用 CRUD」的页面</b>：

```
// 只统计真正调用 store.add/update/patch('资源名', ...) 的写入路径
if (generic.isEmpty()) continue;   // 走专用接口的页面直接跳过
```

而本次线上 Bug 走的是 **`SubjectBindingController` 专用接口**，既不查 `CrudRegistry` 白名单，
也不被原测试扫描 —— **恰好落在盲区里**。

这与 `docs/superpowers/plans/2026-09-25-field-write-audit.md` 第 8 节自己记录的覆盖缺口一致：
> 只校验 onSubmit 内真正走通用 CRUD 的写入……走专用接口的页面不参与校验，避免误报。

**「避免误报」的代价就是「真实问题被漏掉」。** 本检查补齐该路径。

---

## 二、新增内容

| 文件 | 说明 |
|------|------|
| `server/src/test/java/com/wuling/common/DedicatedEndpointConsistencyTest.java` | 新增检查（3 个用例）|
| `.github/workflows/field-write-consistency.yml` | 改为同时运行两个测试类，并补充触发路径 |
| `server/src/test/java/com/wuling/common/FieldWriteConsistencyTest.java` | 补充 javadoc，指向新检查（说明盲区已补）|

### 三个用例

| 用例 | 校验内容 |
|------|---------|
| `directColumnKeysMustBeReturnedByBackend` | 专用接口页面的表格列**直读字段**必须被后端实现真实返回 |
| `pagesCallingDedicatedApiMustBeRegistered` | 调用专用接口的页面必须登记在 `COVERED_PAGES`，防止新页面游离在检查外 |
| `columnParserMustActuallyFindColumns` | 自检：解析器必须真能提取到列，否则测试形同虚设 |

### 已覆盖页面（6 个）

| 页面 | 专用接口 | 后端实现 |
|------|---------|---------|
| `subject/channel/index.vue` | `createSubjectChannel` / `updateSubjectChannel` | `AdminSubjectProfileController` |
| `subject/investor/index.vue` | `createSubjectInvestor` / `updateSubjectInvestor` | 同上 |
| `subject/supplier/index.vue` | `createSubjectSupplier` / `updateSubjectSupplier` | 同上 |
| `subject/platform/index.vue` | `fetchPlatformProfile` / `savePlatformProfile` | `PlatformProfileController` |
| `subject/channel/ChannelStoreDialog.vue` | `fetchChannelStores` / `unbindChannelStores` | `SubjectBindingController` |
| `marketing/points-rule/index.vue` | `fetchSigninRule` | `AdminMarketingConfigController`（**marketing-service**）|

> 注意：签到规则的后端实现在 `marketing-service` 模块，不在 `server`。
> 测试按<b>仓库相对路径</b>直接读文件（不依赖 Maven 模块），故 CI 用 `-pl server` 即可。

---

## 三、关键设计：如何避免「一上线就满屏误报」

这是本检查最大的风险 —— 静态分析极易误报，误报一多就会被人为忽略，检查等于失效。
过程中实测踩到并处理了 3 类误报：

| # | 误报情形 | 处理 |
|---|---------|------|
| 1 | **有自定义 `render` 的列**：如平台页 `key:'appId'` 实际读 `row.appid`，列 key 只是展示标识 | 有 render 的列**不按 key 校验** |
| 2 | **本地派生字段**：如 `balance` 由 `subjectAccounts` 镜像本地算出，本就不来自专用接口 | 白名单 `LOCAL_DERIVED_FIELDS`，**每条必须注明理由** |
| 3 | **多接口页面**：平台页同时用平台档案接口 + 通用 CRUD | 后端字段取<b>并集</b>判断 |

> 白名单刻意要求「注明理由」，避免它退化成「万能兜底」而让检查失去意义。

---

## 四、测试有效性验证（关键）

为避免「永远通过的空壳检查」，**故意注入错误验证测试真的会失败**：

| 实验 | 操作 | 结果 |
|------|------|------|
| 1 | 把 `supplier` 页列 key 改为后端不存在的 `productCountTypo` | ✅ 测试失败，报错精确到文件与字段名 |
| 2 | 从 `COVERED_PAGES` 移除 `investor/index.vue` | ✅ 测试报「未登记」并列出该文件 |
| 3 | 实验后恢复文件 | ✅ SHA256 与实验前**完全一致**，无残留改动 |

实验 1 的实际报错输出：

```
检测到 1 处「专用接口页面读字段与后端不一致」：
页面 src/views/subject/supplier/index.vue 的表格列 key='productCountTypo' 直读 row.productCountTypo，
但后端实现未返回该字段。请二选一：① 改用后端真实返回的字段名；
② 若该字段来自本地计算，在 LOCAL_DERIVED_FIELDS 登记并注明理由。
```

---

## 五、验证结果

| 验证项 | 结果 |
|--------|------|
| `server` 模块全量测试 | **51/51 通过**（48 原有 + 3 新增）|
| CI 精确命令（`-pl server -am -Dtest=...,...`）| **5/5 通过**（2 原有 + 3 新增）|
| 测试自身有效性 | 2 个注入实验均如期失败，恢复后字节一致 ✅ |
| 页面登记完整性 | 6 个专用接口页面全部登记，无遗漏 ✅ |

---

## 六、过程中的一个坑（记录备查）

注入实验后，**单独跑**新测试通过，但**全量跑**却失败，报 `investor/index.vue` 未登记。

**根因**：Maven 增量编译未重新编译已恢复的测试源（`.class` 时间戳比源文件新），
跑的是**注入实验时编译的旧 class** —— 属构建缓存假象，非代码缺陷。

**处理**：删掉旧 `.class` 后重跑即通过。

> 教训：注入实验后若要复验，必须确保重新编译（或看 `.class` 与源文件时间戳）。
> 否则可能得出**相反的错误结论** —— 既可能误判「修好了」，也可能误判「又坏了」。

---

## 七、后续建议

- [ ] 提交本次改动（含 V36 迁移、前端修复、两个测试类、CI 工作流）
- [ ] 后续新增专用接口页面时，按测试报错提示登记 `COVERED_PAGES` 与 `BACKEND_IMPLEMENTATIONS`
- [ ] 可考虑把 `LOCAL_DERIVED_FIELDS` 的「理由」用测试断言强制非空，进一步防退化