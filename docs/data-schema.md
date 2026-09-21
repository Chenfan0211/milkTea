# 五零时光 · 小程序 × 运营后台 数据串联规范（Schema）

> 本文档是「商品」「订单」两个核心实体在 **小程序端（user-h5）** 与 **运营后台（src）** 之间的**唯一字段规范（唯一事实来源）**。
> 后续接入真实后端时，两端与后端接口均以本文档的字段命名为准。

## 一、总体约定

| 项       | 约定                                                                                            |
| -------- | ----------------------------------------------------------------------------------------------- |
| 金额单位 | **分（int）**，两端展示层各自换算为元                                                           |
| 价格字段 | 后台 `price` = 小程序 `price * 100`（分）                                                       |
| 状态枚举 | 后台用**英文大写枚举**，小程序用**中文展示文案 + `orderStatus` 英文枚举**，二者可映射（见下表） |
| 商品 ID  | 统一采用小程序商品 ID（如 `classic-001`），后台 `seed` 与之对齐                                 |
| 订单号   | 后台 `orderNo` = 小程序 `orderInfo.orderNo`                                                     |

## 二、商品（Product）字段对齐

### 后台 `Api.Admin.Product`（src/typings/api/admin.d.ts）

| 字段          | 类型                    | 语义                                        |
| ------------- | ----------------------- | ------------------------------------------- |
| id            | number                  | 后台自增 ID（仅后台内部使用）               |
| productId     | string                  | **商品 ID（对齐小程序，如 `classic-001`）** |
| code          | string                  | 商品编码（后台业务码 `P-1000`）             |
| name          | string                  | 商品名称                                    |
| category      | string                  | 分类（如「鲜奶茶」）                        |
| specCount     | number                  | 规格数                                      |
| price         | number                  | 售价（**分**）                              |
| originalPrice | number                  | 原价（**分**）                              |
| description   | string                  | 商品描述                                    |
| store         | string                  | 归属门店                                    |
| onSale        | 'on' \| 'off'           | 上架状态                                    |
| splitReady    | 'ready' \| 'incomplete' | 分账规则完整度                              |

### 小程序商品（user-h5/data/mock.js → menuTabs → groups → categories → products）

| 字段             | 类型     | 语义             | 后台对应                  |
| ---------------- | -------- | ---------------- | ------------------------- |
| id               | string   | 商品 ID          | → `productId`             |
| name             | string   | 商品名称         | → `name`                  |
| tags             | string[] | 标签             | —（后台暂无，可后续扩展） |
| description      | string   | 描述             | → `description`           |
| price            | number   | 售价（**元**）   | `price/100`               |
| originalPrice    | number   | 原价（**元**）   | `originalPrice/100`       |
| storedValuePrice | number   | 储值会员价（元） | —（扩展字段）             |
| image            | string   | 商品图           | —（素材字段）             |
| specDetail       | object   | 规格详情         | → `specCount` 可由此推导  |

### 映射要点

- 小程序以 **元（小数）** 存储价格，后台以 **分（整数）** 存储，换算关系 `后台 = 小程序 × 100`。
- 商品 ID 两端统一：小程序用 `id`，后台用 `productId`（`id` 仅后台内部自增），取值如 `classic-001`、`classic-005` 等。
- 后台 `onSale` 的 `on/off` 决定小程序商品是否可见（后续接后端后由该字段驱动下架）。

## 三、订单（Order）字段对齐

### 后台 `Api.Admin.Order`（src/typings/api/admin.d.ts）

| 字段       | 类型               | 语义               |
| ---------- | ------------------ | ------------------ |
| id         | number             | 后台自增 ID        |
| orderNo    | string             | 订单号             |
| store      | string             | 门店               |
| user       | string             | 下单用户           |
| summary    | string             | 商品摘要           |
| paidAmount | number             | 实付金额（**分**） |
| status     | enum               | 订单状态（见下表） |
| payStatus  | 'PAID' \| 'UNPAID' | 支付状态           |
| pickupCode | string             | 取餐码             |
| createTime | string             | 创建时间           |

### 小程序订单（user-h5/data/mock.js → orders）

| 字段                 | 类型   | 语义               | 后台对应                                 |
| -------------------- | ------ | ------------------ | ---------------------------------------- |
| id                   | string | 订单 ID            | —（后台为 number，接后端统一为 orderNo） |
| orderInfo.orderNo    | string | 订单号             | → `orderNo`                              |
| storeName            | string | 门店               | → `store`                                |
| title / items[].name | string | 商品摘要           | → `summary`                              |
| amount               | number | 实付（**元**）     | → `paidAmount/100`                       |
| orderStatus          | enum   | 订单状态（见下表） | → `status`                               |
| status               | string | 中文状态文案       | 由 `status` 映射展示                     |
| pickupCode           | string | 取餐码             | → `pickupCode`                           |
| orderInfo.createdAt  | string | 创建时间           | → `createTime`                           |

### 订单状态枚举映射（统一规范）

| 后台 `status` | 小程序 `orderStatus` | 小程序中文文案 | 语义                        |
| ------------- | -------------------- | -------------- | --------------------------- |
| `CREATED`     | `pending_payment`    | 待支付         | 已创建未支付                |
| `PAID`        | `paid`               | 已支付         | 已支付待核销                |
| `VERIFIED`    | `verified`           | 已核销         | 门店已核销                  |
| `COMPLETED`   | `completed`          | 已完成         | 订单完成                    |
| `REFUNDED`    | `refunded`           | 已退款         | 已退款                      |
| —             | `canceled`           | 已取消         | 取消/超时关闭（后台可扩展） |

### 订单映射要点（已实际对齐）

- **orderNo 两端统一**：后台 `orderNo` = 小程序 `orderInfo.orderNo`，如 `WX202609160001`。
- **门店两端统一**：后台 `store` 采用小程序完整店名（`星沙乐运魔方店`、`松雅湖吾悦广场店`、`长沙金茂览秀城店`、`长沙高铁南站店`、`五一广场店`）。
- **金额换算**：后台 `paidAmount`（分）= 小程序 `amount`（元）× 100，如 `2100` ↔ `21`。
- **状态枚举**：后台 `status` 与小程序 `orderStatus` 按上表映射；小程序当前实际使用 `pending_payment`、`completed`、`canceled` 三种，后台 seed 已覆盖全量五种以支撑完整流转。
- 后台关联实体（退款 `refunds`、支付 `payments`、核销 `verifies`、分账快照 `snapshots` 等）的 `orderNo` 字段为后台内部演示数据，暂无与小程序一对一的映射需求。

## 三之二、核销与兑换对齐

门店同时支持两类核销：**点单奶茶核销**与**兑换礼品核销**，统一用 `type` 区分，枚举如下：

| type 值    | 后台展示 | 门店端展示 | 说明                           |
| ---------- | -------- | ---------- | ------------------------------ |
| `order`    | 订单     | 奶茶       | 点单奶茶核销，凭证为取餐码     |
| `exchange` | 兑换     | 兑换       | 兑换礼品核销，凭证为兑换自提码 |

### 核销池（后台 `verifyPool`）

| 字段         | 类型                      | 说明                                                                 |
| ------------ | ------------------------- | -------------------------------------------------------------------- |
| `type`       | `'order' \| 'exchange'`   | 核销类型                                                             |
| `pickupCode` | string                    | 取餐码 / 兑换自提码（两类统一用此字段）                              |
| `orderNo`    | string                    | 订单号（`order` 类指向 `orders.orderNo`，`exchange` 类即自提码本身） |
| `product`    | string                    | 商品名                                                               |
| `spec`       | string                    | 规格                                                                 |
| `amount`     | number                    | 金额（分）；兑换类为 0                                               |
| `points`     | number                    | 兑换类消耗的时光币数                                                 |
| `status`     | `'pending' \| 'verified'` | 待核销 / 已核销                                                      |

### 核销联动规则

- **点单奶茶**：`executeVerify` 通过 `pickupCode` 匹配 `orders` 中 `status === 'PAID'` 的订单，核销后将该订单 `status` 置为 `VERIFIED`，核销记录的 `orderNo` 取真实订单号。
- **兑换礼品**：不联动订单表，仅写入核销记录，`type` 记为 `exchange`。

### 兑换核销池（小程序 `points.js`）

- 存储键 `milkTea:exchange:pool`（Storage 持久化，`globalData.exchangeVerifyPool` 为会话镜像）。
- 条目字段与后台 `verifyPool` 对齐（`pickupCode`/`orderNo`/`product`/`spec`/`verified`），金额单位统一为时光币数。

## 四、数据源现状与联动方式

| 端                | 数据源                                                        | 持久化                                  |
| ----------------- | ------------------------------------------------------------- | --------------------------------------- |
| 运营后台（src）   | Pinia store `src/store/modules/admin/index.ts`，`seed()` 生成 | `localStorage` key `milkTea:admin:data` |
| 小程序（user-h5） | `user-h5/data/mock.js` + `user-h5/utils/orders.js` 内存态     | 无后端，静态 mock                       |

> 当前为**共源对齐**（字段/ID/枚举统一），暂不做跨端实时互通。接后端时，两端统一改为请求同一接口，字段按本文档映射。

## 五、后续接后端约定

1. 接口路径沿用后台 `src/service/api/` 既有定义（如 `/api/v1/admin/product/list`、订单 `/api/v1/admin/order/*`）。
2. 后端返回字段以本文档「后台」列为准；小程序侧在 `user-h5/utils/` 下增加适配层，将接口数据转换为小程序展示结构。
3. 金额统一后端返回**分**，两端展示层换算。

## 六、门店类型字典（StoreType）同步约定

> 用于小程序「加盟合作申请」门店类型下拉，由后台「数据字典」页配置。

### 字段契约（两端统一）

| 字段    | 类型    | 语义                           | 说明                     |
| ------- | ------- | ------------------------------ | ------------------------ |
| id      | number  | 字典自增 ID（后台内部）        | 小程序仅展示，不落库     |
| code    | string  | 类型编码（如 convenience）     | 稳定标识，提交申请时使用 |
| name    | string  | 类型名称（如 便利店）          | 展示名                   |
| sort    | number  | 排序权重（升序）               | 决定下拉顺序             |
| enabled | boolean | 是否启用                       | 停用项不下发             |

### 接口契约

- 路径：`GET /api/v1/app/store-types`
- 响应：`{ code: 0, data: StoreType[], message: 'ok' }`，`data` 仅含 `enabled !== false` 的项，按 `sort` 升序。
- 后台类型定义：`Api.App.StoreType`（`src/typings/api/app.d.ts`），接口函数 `fetchAppStoreTypes`（`src/service/api/app.ts`）。

### 小程序端适配层

- `user-h5/config.js`：`USE_MOCK`（后端未就绪时为 true）、`BASE_URL`、缓存 TTL。
- `user-h5/utils/request.js`：`wx.request` 封装，`USE_MOCK` 时走本地 mock 响应。
- `user-h5/utils/store-types.js`：`getStoreTypes()` 优先读本地缓存（有效期内），否则请求接口，失败回退 `data/mock.js` 默认字典。
- 接入真实后端：将 `config.js` 的 `USE_MOCK` 置 false 并填写 `BASE_URL`，后端按上述接口契约返回即可，小程序端无需再改代码。