# 运营后台：商品接库 + 规格菜单下线 + 全页测试数据

> 日期：2026-09-23
> 状态：待执行
> 前置确认：保留「成本价/平台分佣」两列；测试数据须便于后续测试使用

---

## 一、背景与前置发现

### 1.1 页面数据来源现状

后台列表页数据来自前端 `useAdminStore`，分两类：

- **已接后端**（`REMOTE_RESOURCES`）：productCategories / specs / splitRules / coupons /
  subjects / users / roles / withdrawals / auditLogs 等约 35 个资源，走
  `/api/v1/admin/crud/{resource}` 通用 CRUD。
- **仍读本地假数据**（localStorage）：**products（商品）**、orders、payments、refunds、
  verifies、comments、memberLevels、pointsProducts、giftCards、fundFlows 等。

> 结论：仅往数据库写数据，商品等页面**看不到**；必须先打通通路。

### 1.2 后端商品接口已存在（重要）

`product-service` 已有 `AdminProductController`：

- `GET /api/v1/admin/product/list` → `AdminProductDTO`
- `GET /api/v1/admin/product/categories`

`AdminProductDTO` 已含 `category`（分类名）、`stores`（门店数组）、`specCount`、
`onSale`（on/off）、`splitReady`，组装逻辑在 `ProductQueryService#toAdminProduct`。

**缺**：`costPrice`、`platformCommission` 两个字段与对应表列；以及写操作接口。

### 1.3 阻塞性 Bug：管理端 token 无法通过 product-service 鉴权

| Token | 签发方 | `type` 值 |
|-------|--------|-----------|
| 管理端（后台） | `JwtTokenProvider` | `access` |
| 小程序用户 | `MiniAppTokenProvider` | `mini` |

`ProductSecurityConfig` 把 **小程序拦截器** `MiniAppAuthInterceptor`
（强校验 `type == "mini"`）注册到 **管理端路径** `/api/v1/admin/product/**`：

```java
registry.addInterceptor(miniAppAuthInterceptor)
        .addPathPatterns("/api/v1/admin/product/**");
```

**后果**：后台 token（`access`）访问商品接口 **必然 401「无效的令牌」**。

实测证据（同一 token）：

```
GET /api/v1/admin/crud/productCategories -> 200   （走 server，正确）
GET /api/v1/admin/product/list          -> 401 {"code":8888,"message":"无效的令牌"}
```

> 此前未暴露，是因为商品页读本地假数据、从未真正调用该接口。

**修复方向**：管理端路径改由「管理端 JWT（type=access）」校验，
不复用小程序拦截器。参考 auth-service 的 `JwtAuthenticationFilter` 实现管理端校验器，
或在 product-service 内新增 `AdminAuthInterceptor`。

### 1.4 商品分类关联现状

`product.category_id` 有值，但 18 个商品全部挤在 3 个叶子分类：

| 分类 id | 名称 | type | 商品数 |
|---------|------|------|--------|
| 3 | 草本养生茶 | CATEGORY | 8 |
| 5 | 传统原叶茶 | CATEGORY | 5 |
| 8 | 季节限定 | CATEGORY | 8 |

TAB 层（经典菜单、招牌主打）与 GROUP 层（店长推荐、原叶臻选、招牌热销）无直接商品。

**重名问题**：`classic-001` 与 `featured-001` 均名为「五窨茉莉抹茶」。

**处置**：`featured-001` 改名「镇店茉白」（保留 classic-001 原名）。

### 1.5 规格管理菜单可安全下线

- 菜单页 `src/views/product/spec/index.vue` 操作 `spec_group_template`
  （与商品自身的 `product_spec` 语义不同）。
- 商品规格编辑功能**已内置**在「商品管理」页（`SpecGroupsEditor.vue`）。
- 故下线菜单不影响商品规格维护。

---

## 二、执行计划

### 步骤 1：下线「规格管理」菜单

- `src/router/elegant/routes.ts`：删 `product_spec` 路由块
- `src/constants/admin.ts`：删 `product_spec` 元数据行
- 删目录 `src/views/product/spec/`
- 保留 `spec_group_template` / `spec_option_template` 表与白名单（避免误伤）

### 步骤 2：修复管理端鉴权（阻塞项）

- 在 product-service 新增管理端 JWT 校验（`type == access`），
  替换 `ProductSecurityConfig` 中对小程序拦截器的复用
- 保留小程序路径不受影响
- 需同步核对其他服务是否有同类误用

### 步骤 3：补 `cost_price` / `platform_commission` 两列

- 迁移新增两列（`bigint`，单位：分）
- `Product` 实体 + `AdminProductDTO` + `toAdminProduct` 同步
- 前端商品列表恢复显示这两列

### 步骤 4：商品写操作接口

- `AdminProductController` 增加：新增 / 编辑 / 上下架 / 删除
- 写 `product` 表；上下架映射 `on_sale` 0/1
- 规格组读写落 `product_spec`；门店关联落 `product_store`

### 步骤 5：前端接入

- `products` 加入 `REMOTE_RESOURCES`
- 商品页 `loadData` 改走 `/api/v1/admin/product/list`
- 字段单位对齐（分 ↔ 元）

### 步骤 6：V22 测试数据（每页 10~20 条）

**补空表：**

| 页面 | 表 | 现状 → 目标 |
|------|-----|-----------|
| 审核·角色开通审核 | `role_application` | 0 → 12 |
| 财务·对账异常池 | `reconcile_issue` | 0 → 12 |
| 财务·资金池 | `fund_pool` | 0 → 6 |
| 营销·分享有礼 | `referral_record` | 0 → 15 |

**补不足：**

| 页面 | 表 | 现状 → 目标 |
|------|-----|-----------|
| 商品管理 | `product` + `product_store` | 18 → 30 |
| 订单 / 支付 / 退款 | `orders`/`payment`/`refund` | 16/11/2 → 各 20 |
| 核销 | `verify_record` | 13 → 20 |
| 评论审核 | `comments` | 2 → 15（含待审）|
| 会员等级 | `member_level` | 4 → 10 |
| 用户优惠券 | `user_coupon` | 1 → 15 |
| 积分记录 | `points_record` | 4 → 15 |
| 提现管理 | `withdrawal` | 13 → 20 |

**数据规范（便于后续测试）：**

- 统一 `DEMO-` 前缀，与真实业务数据区分，可一键清理
- 金额单位统一为**分**
- 枚举对齐后端 Java 定义（withdrawal: APPLIED/APPROVED/PAID/REJECTED；
  settlement: PENDING/SETTLED/CANCELED）
- 覆盖关键状态：待审核 / 已通过 / 已拒绝 / 异常，便于测试筛选与分页
- **全部幂等**：可重复执行，不报错、不重复插入
- 附带清理 SQL（按 `DEMO-` 前缀或明确 ID 区间删除）

### 步骤 7：验证

- `pnpm typecheck`
- 逐页核对数据量
- 商品接口实测（含鉴权修复后的 200）
- 上线后回归

---

## 三、风险与约束

| 项 | 说明 |
|----|------|
| 服务中断 | 步骤 2/3/4 需重建 `product-service` 镜像并重启，约数十秒 |
| 数据库变更 | 步骤 3 加列、步骤 6 灌数据，均走 Flyway，需备份 |
| 鉴权改动 | 步骤 2 涉及安全逻辑，改后须验证「管理端可访问 + 小程序不受影响 + 匿名仍被拦」 |
| 回滚 | 前端备份 /opt/wuling/backup/；数据逻辑删除或反向 SQL |

## 四、验收标准

1. 规格管理菜单消失，商品规格编辑功能仍可用
2. 后台可正常查询/维护商品（含成本价、平台分佣）
3. 商品按分类合理分布，无重名
4. 各页面均有 10~20 条可辨识的测试数据
5. 测试数据可一键清理，不影响真实业务数据
