# 后台配置类接口（第 5 批）— 授权 / 交易 / 财务 / 审核

> 本批是后台接入的收尾：把剩余 4 个模块（授权、交易、财务、审核）接上接口。

## 一、补齐的后端接口

### 财务查询（`AdminFinanceQueryController`）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/finance/pool` | 资金池（无记录时返回汇总视图） |
| GET | `/api/v1/admin/finance/flows` | 资金流水（分页，支持 subjectId/type） |
| GET | `/api/v1/admin/finance/snapshots` | 分账快照（支持 orderNo） |
| GET | `/api/v1/admin/finance/reconcile` | 对账异常 |
| GET | `/api/v1/admin/finance/accounts/page` | 主体账户分页 |
| POST | `/api/v1/admin/finance/accounts/{subjectId}/freeze` | 冻结（余额不足返回 400） |
| POST | `/api/v1/admin/finance/accounts/{subjectId}/unfreeze` | 解冻 |

### 交易查询（`AdminTradeQueryController`）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/trade/payments` | 支付单 |
| GET | `/api/v1/admin/trade/refunds` | 退款单 |
| GET | `/api/v1/admin/trade/verify-records/page` | 核销记录 |
| GET | `/api/v1/admin/trade/verify-pool/page` | 核销池（含商品摘要聚合） |

### 授权 / 审计（`AdminAuthQueryController`）
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/auth/roles` | 后台角色 |
| GET | `/api/v1/admin/auth/grants` | 用户-角色授权（联表带主体名/用户名） |
| GET | `/api/v1/admin/auth/wechat` | 微信绑定 |
| GET | `/api/v1/admin/auth/audit` | 审计日志 |
| POST | `/api/v1/admin/auth/audit` | 写入审计日志 |

## 二、CRUD 白名单继续扩展

资源从 21 个扩展到 **42 个**，新增：

| 分类 | 资源 |
|------|------|
| 授权 | `roles`、`grants` |
| 交易 | `orders`、`payments`、`refunds`、`verifies`、`verifyPool`、`verifyRecords`、`exchangeRecords` |
| 财务 | `fundPool`、`fundFlows`、`snapshots`、`reconciles`、`subjectAccounts`、`withdrawals` |
| 审核/系统 | `roleApplications`、`comments`、`commentsAdmin`、`auditLogs` |
| 营销 | `referralConfig`、`signinRules` |

## 三、页面接入（16 个）

| 模块 | 页面 | remoteKey |
|------|------|-----------|
| 交易 | order | orders |
| | payment | payments |
| | refund | refunds |
| | verify | verifies |
| | verify-pool | verifyPool |
| 授权 | auth/role | roles |
| | auth/grant | grants |
| | auth/wechat | users |
| 审核 | review/role | roleApplications |
| 系统 | system/audit | auditLogs |
| 财务 | finance/account | subjectAccounts |
| | finance/flow | fundFlows |
| | finance/reconcile | reconciles |
| | finance/snapshot | snapshots |
| | finance/withdraw | withdrawals |
| | finance/pool | （自定义页，onMounted 加载 fundPool + subjectAccounts） |

## 四、验证结果

### 资源与查询
```
资源总数   : 42 个

财务       : 资金池余额=13631  流水=74  快照=9  对账=0  账户=6
交易       : 支付单=11  退款单=2  核销记录=10  核销池=1
授权/审计  : 角色=2  授权=0  微信绑定=2  审计=0
```

### 核销池（含商品摘要）
```json
{ "orderNo": "WX202609212012558608", "pickupCode": "0002",
  "paidAmount": 1390, "status": "PAID", "summary": "金桂轻乳茶 x1" }
```

### 账户冻结/解冻
```
冻结 100 : code=0
解冻 100 : code=0
超额冻结 : 400 余额不足或账户不存在 ✅
```

### 回归
| 项目 | 结果 |
|------|------|
| 后台 `vue-tsc` | 通过 |
| 后台 `oxlint`（212 文件） | 0 error |
| 后端 `mvn test` | 16/16 通过 |
| 数据库一致性校验 | 3/3 通过 |

## 五、本批修复的缺陷

1. **核销池 SQL 拼接错误**：用 `where.substring(indexOf("and"))` 拼 SQL，导致生成 `... and and o.status` 语法错误，接口 500。已重写为直接构造完整 where 子句。
2. **函数命名冲突**：新增的 `fetchAdminRoles`/`fetchAdminGrants`/`fetchAdminWechat` 与既有 `auth_admin.ts` 同名，触发 `TS2308 重复导出`。已重命名为 `fetchRolesPage`/`fetchGrantsPage`/`fetchWechatBindingsPage`。

## 六、改动文件

**后端新增**
- `finance/controller/AdminFinanceQueryController.java`
- `trade/controller/AdminTradeQueryController.java`
- `auth/controller/AdminAuthQueryController.java`

**后端修改**
- `system/crud/CrudRegistry.java`（资源 21 → 42）

**前端修改**
- `src/service/api/crud.ts`（新增 18 个查询/动作函数）
- `src/store/modules/admin/index.ts`（资源映射扩展）
- 16 个页面（remoteKey / onMounted）

## 七、后台接入完成度

| 批次 | 模块 | 页面数 | 状态 |
|------|------|--------|------|
| 1 | 系统配置 | 4 | ✅ |
| 2 | 主体管理 | 5 | ✅ |
| 3 | 商品配置 | 4 | ✅ |
| 4 | 营销配置 | 10 | ✅ |
| 5 | 授权 / 交易 / 财务 / 审核 | 16 | ✅ |
| **合计** | | **39** | 已接入 |

剩余未接入：
- 详情页（order-detail / verify-detail / snapshot-detail / role-detail）复用列表页数据，无需单独接口
- `review/role-detail` 的角色申请审核动作已具备接口（`/subject/binding/application/{id}/review`）

## 八、仍待完成（非本批范围）

1. **鉴权细化**：`/api/v1/app/**` 当前整体放行（演示阶段），接入微信登录后需改为 JWT + userId 归属校验
2. **真实支付**：`MockPaymentGateway` 需替换为微信支付实现
3. **真实出款**：提现出款为状态标记，需接微信商家转账 API
4. **部署上线**：Nginx + HTTPS + 域名（依赖 ICP 备案）
