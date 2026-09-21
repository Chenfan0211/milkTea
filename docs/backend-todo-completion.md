# 五零时光后端 · 遗留项补全（第四批）

> 内容：① 供应商归属建模 + 明细分摊　② 积分兑换落库
> 决策：①A 按明细分摊　②B 未绑定供应商拒绝分账

## 一、供应商归属建模

### 数据模型（V7 迁移）

```sql
ALTER TABLE product
    ADD COLUMN supplier_subject_id BIGINT UNSIGNED NULL COMMENT '供应商主体ID',
    ADD KEY idx_product_supplier (supplier_subject_id);
```

- 商品 → 供应商为 **1:1**（`product.supplier_subject_id`）
- `supplier_profile.product_count` 改为由实际归属统计
- 种子：18 个商品均分给 401/402/403 三个供应商（各 6 个）

### 分账算法：按明细分摊（①A）

供应商份额不再取整单比例，而是**逐条明细分摊后汇总**：

```
supplier_amount = Σ(该明细小计 × supplierRatio / 10000)
```

- 每条明细向下取整到分，**尾差归平台**
- 多商品订单可正确归属到**不同供应商**
- 无明细时供应商份额为 0（尾差归平台），总额恒等于实付

### 未绑定供应商的处理（②B）

**校验前移到下单**，而非核销时：

| 环节 | 行为 |
|------|------|
| 下单 | 商品未绑定供应商 → `400 商品未配置供应商，暂不可下单` |
| 核销分账 | 兜底拦截 → `400 订单存在未绑定供应商的商品，无法分账` |

> 说明：②B 若只在核销侧拦截，会出现「用户已付款、门店核销失败」的死结。
> 因此主校验放在下单，核销侧保留兜底。

## 二、积分兑换落库

`PointsService.exchange()` 由「仅返回自提码」改为**完整落库**：

- 写入 `exchange_order`（exchangeNo / userId / pointsProductId / points / pickupCode / status=PENDING）
- 自提码即 `exchange_no`（`CZ` + 时间戳），与订单一一对应
- 门店核销 `type=EXCHANGE` 时**联动兑换单**置 `VERIFIED`（幂等由兑换单状态保证）

新增接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/app/points/exchange` | 兑换，返回兑换单实体 |
| GET | `/api/v1/app/points/exchange-orders` | 我的兑换记录 |

## 三、验证记录

### 多商品跨供应商分账（核心验证）

订单含 `classic-001`（供应商 401）与 `herbal-002`（供应商 402），实付 2780 分：

| 项目 | 值 |
|------|-----|
| 快照 supplier_amount | **278** 分 |
| 五方之和 | 2780 分 = 实付 ✅ |
| 明细分摊 | 1390×10% + 1390×10% = 139 + 139 = 278 ✅ |
| 供应商入账 | 401 → 139 分，402 → 139 分 ✅ |

### ②B 拒绝场景

```
商品 classic-004 供应商置空 -> 下单返回 400 商品未配置供应商，暂不可下单 ✅
```

### 积分兑换

```
兑换     : exchangeNo=CZ1789994253897 points=20 status=PENDING ✅
核销     : type=EXCHANGE 联动兑换单 -> VERIFIED ✅
重复核销 : 400 兑换码已核销，请勿重复核销 ✅
```

### 回归

| 项目 | 结果 |
|------|------|
| 单元测试 | 16/16 通过（含 3 个新增供应商分摊测试） |
| 数据库一致性校验 | 3/3 通过 |
| 历史分账快照 | **8/8 全部一致**（改动未破坏存量数据） |
| 商品供应商覆盖 | 18 个商品，0 个缺失 |

## 四、改动文件

**新增**
- `db/migration/V7__product_supplier.sql`

**修改**
- `SplitCalculator`：新增 `LineItem` 与明细分摊重载
- `LedgerService`：`executeSplit` 接收明细，供应商按主体分别入账，未绑定则拒绝
- `OrderService`：下单前置校验供应商绑定
- `VerifyService`：核销构造明细传入分账；EXCHANGE 联动兑换单
- `PointsService`：`exchange()` 落库 + `verifyExchange()` + `exchangeOrders()`
- `Product` 实体：新增 `supplierSubjectId`
- `AppMarketingController`：兑换接口返回兑换单 + 兑换记录查询

## 五、剩余遗留（供后续决策）

1. **支付为 Mock**：接微信支付时新增 `WxPayGateway implements PaymentGateway`
2. **提现出款为状态标记**：真实出款需接微信商家转账 API
3. **供应商结算周期的业务规则**：当前与门店/渠道同一套 T+1 规则，如需差异化（如月结）需扩展
