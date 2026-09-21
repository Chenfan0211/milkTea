# 五零时光后端 · P1 + P2（第三批）

> P1：四类角色工作台　P2：提现 / 优惠券 / 储值 / 礼品卡 / 积分 / 评论
> 状态：已完成并通过端到端验证

## 一、P1 四类角色工作台

> 数据按主体隔离，各角色使用独立路径前缀，避免路由歧义。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/app/workbench/subject/{id}/overview` | 概览：可用余额/冻结/累计收入/今日订单/待结算/可结算 |
| GET | `/api/v1/app/workbench/subject/{id}/flows` | 资金流水（收益、提成、结算明细） |
| GET | `/api/v1/app/workbench/subject/{id}/settlements` | 结算台账 |
| GET | `/api/v1/app/workbench/store/{id}/orders` | 门店订单 |
| GET | `/api/v1/app/workbench/channel/{id}/stores` | 渠道绑定门店 |
| GET | `/api/v1/app/workbench/channel/{id}/orders` | 渠道归因订单 |
| GET | `/api/v1/app/workbench/investor/{id}/stores` | 投资人投资门店 |

验证结果：

```
门店概览   : role=STORE 可用=0 待结算=0 今日订单=3
渠道门店   : 2 家 -> 星沙乐运魔方店, 松雅湖吾悦广场店
渠道订单   : 4 条（按绑定门店归因）
投资人门店 : 2 家 -> 星沙乐运魔方店, 广州天河城店
```

## 二、P2 营销与资金

### 提现闭环（`WithdrawalService`）

规则对齐 `user-h5/data/role-mock.js`：

| 场景 | 行为 |
|------|------|
| 单笔 ≤ 100 元 | 小额即时到账（`PAID`），无需审核 |
| 单笔 > 100 元 | 进入审核（`APPLIED`） |
| 审核通过 | 出款，从冻结扣减，计入累计提现 |
| 审核驳回 / 出款失败 | **自动解冻**回可用余额（`UNFREEZE` 流水） |
| 余额不足 | 拒绝 |

验证记录（金额单位：分）：

```
超限提现 11000 -> 可用=205 冻结=11000  (期望 APPLIED ✅)
驳回           -> 可用=11205 冻结=0     (自动解冻 ✅)
重复审核       -> 400 该提现申请已处理，不能重复审核 ✅
审核通过       -> 可用=205 冻结=0 累计提现=13000 ✅
```

### 优惠券（`CouponService`）

状态机 `UNUSED → LOCKED → USED`，取消订单走 `releaseByOrder` 回退。
- 领取扣库存，同一用户同一券只能持有一张未使用（重复领取返回 400）
- 锁券校验使用门槛，抵扣金额不超过订单金额

### 积分 / 时光币（`PointsService`）

- `change()` 为唯一入口，保证余额与流水强一致
- 签到每日一次（重复签到返回 400）
- 兑换扣库存 + 扣币，生成自提码

### 储值 / 礼品卡

- 储值充值余额入账；**购买时不分账**，核销消费时才分账（预收资金不提前分配）
- 礼品卡购买生成卡号，状态 `ACTIVE`

### 评论（`CommentService`）

`PENDING → APPROVED/REJECTED`，一单一评（重复提交返回 400），重复审核被拒。

## 三、接口总览（本批新增）

**小程序端**：优惠券（3）、储值（3）、礼品卡（3）、积分（5）、用户（1）、评论（1）、工作台（7）、提现（3）

**后台端**：订单、核销、退款、分账账户、结算、提现审核（4）、评论审核（2）

## 四、验证总览

### 单元测试
```
Tests run: 13, Failures: 0, Errors: 0  (BUILD SUCCESS)
```

### 数据库一致性校验（`LedgerConsistencyIT`，连真实库）
```
分账快照五方之和 = 实付金额
分账规则比例合计 = 10000
可用余额/冻结金额均非负
Tests run: 3, Failures: 0, Errors: 0  (BUILD SUCCESS)
```

### 数据核对
| 项 | 结果 |
|----|------|
| 分账快照一致性 | 7/7 全部一致 |
| 台账 SETTLEABLE | 23 条 / 26631 分 |
| 提现 PAID | 3 笔 / 13000 分 |
| 提现 REJECTED | 1 笔 / 11000 分（已解冻） |
| 表数量 | 58 |

## 五、本批修复的缺陷

1. **路由歧义**（严重）：`/{subjectId}/stores` 与 `/channel/{id}/stores` 冲突，导致 `/channel/301/stores` 被匹配成 `subjectId=channel`，返回 `NoResourceFoundException`。已改为显式前缀 `subject/store/channel/investor`。
2. **MyBatis-Plus API 兼容**：`selectBatchIds`/`selectByIds` 在当前版本不可用，改用 `selectList + in`。

## 六、已知限制（后续批次）

1. **供应商分账未入账**：商品→供应商归属关系未建模，该份额归平台兜底
2. **支付为 Mock**：接入微信支付时新增 `WxPayGateway` 实现 `PaymentGateway` 即可
3. **积分兑换未写 exchange_order**：当前仅扣币扣库存并返回自提码
4. **提现出款为即时标记**：真实出款需接入微信商家转账 API
5. **定时结算**：`SettlementJob` 每日 01:00，联调可用 `/api/v1/admin/finance/settle`
