# 假数据清理（补齐运营配置与城市接口）

> 目标：排查并补齐所有残留假数据，为部署做准备

## 一、排查结论（清理前）

### 小程序端：30 个文件引用 mock

按性质分三类：

| 类别 | 处理 |
|------|------|
| **① 真业务数据**（订单/积分/优惠券/储值/礼品卡/会员等级/用户资料） | 改走接口 |
| **② 运营配置**（首页入口/活动说明/我的页宫格/签到规则/城市/活动规则） | **新建接口** |
| **③ 合理本地数据**（购物车、固定枚举分类、工具函数） | 保持不动 |

### 后台端：44 个页面中 34 已接、10 未接

未接的 10 个中，7 个是**详情页**（复用列表数据），3 个是需要单独处理的（home 仪表盘、user/list、referral）。

## 二、本批新增

### 1. V10 迁移：运营配置表

```sql
CREATE TABLE app_config (
    config_key  VARCHAR(64) NOT NULL,   -- 配置键
    config_name VARCHAR(64) NOT NULL,   -- 后台展示名
    value       JSON NULL,               -- 配置内容
    ...
);
```

种子数据：`home_shortcuts`、`menu_activity`、`profile_functions`、`signin_rules`、`signin_rewards`、`app_cities`

> 设计理由：这些是**运营可配置内容**，用统一 key-value 表承载，后台可编辑而无需发版，也避免为每类配置单独建表。

### 2. 新接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/app/config/home` | 首页快捷入口 + 点单活动 |
| GET | `/api/v1/app/config/profile` | 我的页功能宫格 |
| GET | `/api/v1/app/config/cities` | 城市列表（含坐标） |
| GET | `/api/v1/app/config/signin` | 签到规则 + 奖励档位 |
| GET | `/api/v1/app/config/{key}` | 通用单项读取 |
| GET | `/api/v1/app/config/batch?keys=` | 批量读取（减少请求数） |

CRUD 白名单新增 `appConfig`，后台可直接编辑。

## 三、接入的页面（15 个）

| 页面 | 接入内容 |
|------|---------|
| home | 快捷入口、用户资料 |
| profile | 功能宫格、手机号（脱敏） |
| city-picker | 城市列表（接口 + 本地缓存） |
| orders | 订单列表 |
| points-mall | 积分商品 |
| points-detail | 时光币流水 |
| points-signin | 签到奖励档位 |
| points-signin-rules | 签到规则文案 |
| coupon-products | 优惠券 |
| coupon-stores | 优惠券 |
| stored-value | 储值套餐 |
| gift-card | 礼品卡面额 |
| gift-card-purchase | 礼品卡面额 |
| member | 会员等级 |
| exchange-records | 兑换记录 |

## 四、验证结果

### 真实接口联调（USE_MOCK=false，17/17 通过）
```
/api/v1/app/config/home            shortcuts=4
/api/v1/app/config/profile         functions=8
/api/v1/app/config/cities          cities=3
/api/v1/app/config/signin          rules=2 rewards=1
/api/v1/app/menu                   tabs=2
/api/v1/app/stores                 stores=5
/api/v1/app/coupons                coupons=3
/api/v1/app/member-levels          levels=3
/api/v1/app/stored-value/packages  packages=3
/api/v1/app/gift-cards/denominations denoms=3
/api/v1/app/points/products        pointsProducts=2
/api/v1/app/points/rules           rules=6
/api/v1/app/points/records         records=3      （需登录）
/api/v1/app/orders                 orders=11      （需登录）
/api/v1/app/users/0/coupons        userCoupons=1  （需登录）
/api/v1/app/points/exchange-orders exchangeOrders=1（需登录）
/api/v1/app/gift-cards             giftCards=1    （需登录）
```

### 回归
| 项目 | 结果 |
|------|------|
| 小程序 `npm run check` | 22 项通过 |
| 后端 `mvn test` | 16/16 通过 |
| 数据库一致性校验 | 3/3 通过 |

## 五、本批修复的缺陷

**MySQL JSON 列返回「字符串化数组」**：
`app_config.value` 是 JSON 列，经 `JdbcTemplate` 读取得到的是 `String`，导致接口返回 `"[{...}]"` 字符串而非数组（前端 `shortcuts.length` 得到 344 而非 4）。

修复：`readValue` 中反序列化为真正的 JSON 结构再返回。

> 这个缺陷很隐蔽——接口 `code=0` 看起来正常，但前端遍历会失败。

## 六、剩余「mock 引用」说明（非缺陷）

小程序端仍有 19 个文件 `require mock`，但**已不是唯一数据源**，而是：

| 用途 | 示例 |
|------|------|
| 接口兜底/初始值 | 页面先渲染本地数据，接口返回后覆盖 |
| 固定枚举 | `orderCategories`（全部/门店/储值/礼品卡）、`pointsCategories`、`exchangeRecordCategories` |
| 客户端状态 | `initialCartItems`（购物车） |
| 工具函数 | `formatOrderAmount` |
| 缓存回退 | `storeTypes`、`stores`、`cities` |

> 这是**符合设计**的：`USE_MOCK=true` 时走本地、`false` 时走接口，同一套代码双模运行。

## 七、改动文件

**后端新增**
- `db/migration/V10__app_config.sql`
- `system/controller/AppConfigController.java`

**后端修改**
- `system/crud/CrudRegistry.java`（新增 appConfig 资源）

**前端修改**
- `utils/api.js`（+5 个配置接口，共 51 个）
- `utils/store.js`（`refreshCitiesFromRemote` / `getCityList`）
- 15 个页面接入

## 八、部署前检查清单

| 项 | 状态 |
|----|------|
| 后端接口全通（17/17） | ✅ |
| 小程序测试通过 | ✅ |
| 后台类型检查 + lint | ✅ |
| 数据库一致性 | ✅ |
| 敏感配置不入库（AppSecret） | ✅ |
| 迁移脚本 V1–V10 | ✅ |
| **ICP 备案** | ⏳ 待确认 |
| **腾讯云短信配置** | ⏳ 待申请 |
| **微信支付商户号** | ⏳ 待申请 |
