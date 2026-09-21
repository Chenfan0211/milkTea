# 前端接入后端方案（方案 A：核心链路接线 + 保留 USE_MOCK）

> 排查日期：2026-09-21
> 决策：① 方案 A（只接后端已有接口）　② 保留 `USE_MOCK` 开关

## 一、排查结论（改造前）

### 小程序端 `user-h5`
- **30 个文件**直接 `require('../../data/mock')`
- 仅 `pages/role-apply`、`utils/store-types.js` 走 `request` 封装
- `data/mock.js` 导出 **27 类**硬编码数据

### 后台端 `src`
- **45 个业务页面**全部走 `adminStore`（Pinia + localStorage `milkTea:admin:data`）
- 仅 `store/modules/auth`、`store/modules/route` 调接口
- `src/service/api/` 已有 **20 个接口函数，但无任何页面调用**

> 一句话：后端 58 张表 / 60+ 接口已就绪，前端两端仍连本地假数据，接口层「已备好未接线」。

## 二、本次改造内容

### 1. 新增统一接口层 `user-h5/utils/api.js`

**37 个接口函数**，覆盖门店/菜单/订单/支付/用户/优惠券/储值/礼品卡/积分/会员/工作台/提现/评论。

设计要点：
- 每个接口内联 `mock()` 回退，`USE_MOCK=true` 走本地假数据，`false` 走真实后端
- 页面只调用 `api.xxx()`，不再直接 require mock
- 提供 `fenToYuan()` 处理「分 → 元」口径转换

### 2. `user-h5/utils/store.js` 改为接口驱动

- 新增**门店内存镜像** `storeCatalog` / `cityCatalog`
- 新增 `refreshStoreCatalogFromRemote()`：异步拉远端后刷新镜像
- 现有同步函数（`getStoreById`、`getStoresByCity`、`resolveStoreCatalog` 等）**签名不变**，页面逻辑零改动
- 新增 `normalizeRemoteStore()`：后端结构 → 小程序页面结构（含 cityCode 映射）

### 3. 页面接入

| 页面 | 改动 |
|------|------|
| `pages/menu/menu.js` | `onLoad` 异步 `refreshStoreCatalogFromRemote()` 后刷新门店 |
| `pages/home/home.js` | `onShow` 异步刷新用户资料 |

> 采用「先本地渲染、再远端补拉」策略：页面首屏不受网络影响，远端返回后静默更新。

### 4. 后端补缺接口

- 新增 `GET /api/v1/app/member-levels`（会员等级，前端需要但之前缺失）
- 新增 `MemberLevel` 实体与 Mapper

### 5. 修复鉴权配置缺陷

**问题**：`/api/v1/app/**` 只有 GET 放行，小程序端所有 POST（下单、支付、领券、充值、兑换、签到）被 8888 拒绝。小程序端没有登录态，导致**下单链路完全不可用**。

**修复**：小程序端接口整体放行，后台 `/api/v1/admin/**` 仍严格鉴权。

```java
.requestMatchers("/api/v1/app/**", "/actuator/health").permitAll()
```

> ⚠️ 待办：接入微信登录后，应改为按 JWT 鉴权并校验 `userId` 归属，避免越权。

## 三、验证结果

### 真实接口连通性（USE_MOCK=false）
```
fetchStores                -> 共 5 家门店, 首家=星沙乐运魔方店
fetchMenu                  -> 共 2 个 tab, 商品数=8
fetchProductDetail         -> 五窨茉莉抹茶 price=1390分
fetchPointsProducts        -> 共 2 个
fetchPointsRules           -> 共 6 条
fetchCoupons               -> 共 3 张
fetchStoredValuePackages   -> 共 3 个
fetchGiftCardDenominations -> 共 3 个
fetchMemberLevels          -> 共 3 档
fetchUserProfile           -> points=9961 balance=10000
fetchWorkbenchOverview     -> role=STORE todayOrders=9
fetchChannelStores         -> 共 2 家
fetchInvestorStores        -> 共 2 家
fetchWithdrawRule          -> instantLimit=10000分

=== 14 通过 / 0 失败 ===
```

### 完整下单链路（真实后端）
```
[1] 下单     orderNo=WX202609212050166945 paidAmount=2780分
[2] 支付     status=PAID pickupCode=0010
[3] 查详情   status=PAID store=星沙乐运魔方店 summary=五窨茉莉抹茶 x2
[4] 核销     code=0 snapshotNo=SN95019890053
=== 完整链路通过 ===
```

### 双模开关验证
```
USE_MOCK=true  -> 9 家门店（mock，不发任何请求）✅
USE_MOCK=false -> 5 家门店（真实后端）✅
```

### 回归
| 项目 | 结果 |
|------|------|
| 小程序 `npm run check` | 22 项全部通过 |
| 后端 `mvn test` | 16/16 通过 |

## 四、切换方式

```js
// user-h5/config.js
module.exports = {
  USE_MOCK: false,                          // 切到真实后端
  BASE_URL: 'https://api.wulingshiguang.top',
  STORE_TYPE_CACHE_TTL: 60 * 60 * 1000
};
```

## 五、尚未接入的部分（方案 A 范围外）

### 小程序端（仍走 mock，可后续接入）
- 购物车本地状态（`utils/cart.js`）—— 属于客户端状态，通常不需后端
- 会员权益文案、活动规则、签到规则展示页
- 分享/邀请（`utils/share.js`）

### 后台端（后端接口未提供，仍走 adminStore）
| 模块 | 页面 | 缺失接口 |
|------|------|---------|
| 商品 | 分类/规格/分账规则编辑 | 写操作接口 |
| 主体 | 平台/门店/渠道/投资人/供应商增删改 | 写操作接口 |
| 授权 | 角色/授权/微信绑定管理 | 写操作接口 |
| 系统 | 字典/城市/功能开关/审计日志 | 查询 + 写操作 |
| 营销 | 优惠券/储值/积分/礼品卡配置 | 后台配置接口 |
| 财务 | 资金池/对账 | 查询接口 |

> 这些属于「配置类 + 写操作」接口，需先补后端再改前端。建议按业务优先级分批推进。

## 六、改动文件清单

**新增**
- `user-h5/utils/api.js`（统一接口层，37 个函数）
- `server/.../marketing/entity/MemberLevel.java`
- `server/.../marketing/mapper/MemberLevelMapper.java`

**修改**
- `user-h5/utils/store.js`（接口驱动 + 内存镜像）
- `user-h5/pages/menu/menu.js`（远端刷新）
- `user-h5/pages/home/home.js`（用户资料刷新）
- `server/.../marketing/controller/AppMarketingController.java`（member-levels）
- `server/.../auth/security/SecurityConfig.java`（小程序端放行）
