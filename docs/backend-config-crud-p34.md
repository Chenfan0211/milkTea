# 后台配置类接口（第 3、4 批）— 商品配置 + 营销配置

> 第 3 批：商品配置（分类 / 规格组 / 分账规则）
> 第 4 批：营销配置（优惠券 / 储值 / 积分 / 礼品卡 / 会员等级）

## 一、两个语义冲突的处理（开工前确认）

### ① 分账规则口径统一为万分比

| | 原前端 | 后端引擎 |
|---|--------|---------|
| 字段 | 门店/件(元)、投资人比例(%) | `store_ratio` 等**万分比** |
| 校验 | 无 | 五方合计必须 = 10000 |

**决策 ①A**：统一为万分比，保留后端引擎（已被 16 个单测 + 一致性校验覆盖，且 9 条分账快照在用）。
前端页面字段改为百分比录入（`50%` ↔ `5000`）。

**实测校验生效**：
```
启用 SR-BAD（合计 5000） -> 400 分账比例合计必须为 10000（万分比），当前为 5000 ✅
启用 SR-1001（合计 10000）-> code=0 ✅
```

### ② 规格组模板与商品规格分离

| 概念 | 表 | 语义 |
|------|-----|------|
| **规格组模板** | `spec_group_template` + `spec_option_template` | 后台维护，如「杯型：中杯/大杯」 |
| **商品规格明细** | `product_spec` | 商品级，18 商品 × 106 条 |

**决策 ②A**：为「规格管理」页面新增资源指向模板表，避免把模板数据写进商品明细表。

**实测互不干扰**：新增规格组模板后，`product_spec` 仍为 106 条。

## 二、CRUD 白名单扩展

资源从 7 个扩展到 **21 个**（含前两批）：

| 批次 | 资源 | 表 |
|------|------|-----|
| 第3批 | `productCategories` | product_category |
| | `specs` / `specGroups` | spec_group_template |
| | `specOptions` | spec_option_template |
| | `splitRules` | split_rule（万分比） |
| 第4批 | `coupons` | coupon |
| | `storedValuePackages` | stored_value_package |
| | `pointsProducts` | points_product |
| | `pointsEarningRules` | points_earning_rule |
| | `giftCards` / `giftCardDenominations` | gift_card_denomination |
| | `giftCardOrders` | gift_card_order |
| | `memberLevels` | member_level |

## 三、4 个特殊业务接口

通用 CRUD 覆盖不了的动作，单独实现：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/admin/marketing/config/split-rule/{id}/toggle` | 启停分账规则，**校验万分比合计=10000**，同 scope 唯一启用 |
| GET/POST | `/admin/marketing/config/signin-rule` | 签到规则（兼容 `daily/streak` 与 `rewards` 两种入参） |
| GET/POST | `/admin/marketing/config/referral-config` | 邀请配置 |
| POST | `/admin/marketing/config/comment/{id}/review` | 评论审核（重复审核拦截） |

## 四、页面接入（共 10 个）

| 页面 | remoteKey |
|------|-----------|
| product/category | productCategories |
| product/spec | specs |
| product/split | splitRules |
| product/list | productCategories |
| marketing/coupon | coupons |
| marketing/stored | storedValuePackages |
| marketing/points | pointsProducts |
| marketing/points-rule | pointsEarningRules（含 onMounted 拉签到规则） |
| marketing/gift | giftCards |
| marketing/member | memberLevels |

## 五、V9 迁移内容

- **规格组模板初始化**：4 个规格组（份量/温度/甜度/加料）+ 13 个选项
- **分账规则种子**：新增 SR-1001（高毛利商品）、SR-1002（渠道专享），默认停用

## 六、验证结果

### 资源读取
```
productCategories -> 8      coupons             -> 3
specs             -> 4      storedValuePackages -> 3
specOptions       -> 13     pointsProducts      -> 2
splitRules        -> 3      pointsEarningRules  -> 6
                            giftCards           -> 3
                            giftCardOrders      -> 1
                            memberLevels        -> 3
```

### CRUD 写入（含清理）
```
新增规格组模板 : code=0 id=5
新增规格选项   : code=0 groupId=5 priceDelta=100
新增分账规则   : code=0 platformRatio=2000 storeRatio=4000
新增会员等级   : code=0 levelCode=Lv4
新增优惠券     : code=0 amount=1000
删除全部测试数据: 5/5 成功
```

### 特殊动作
```
分账规则启停 : 非法比例被拒(400) / 合法规则启用成功 ✅
签到规则     : 读取 1/7/20 -> 保存 -> 重读 2/10/50 ✅
邀请配置     : 保存后重读 {"dailyLimit":2,...} ✅
评论审核     : 审核通过 -> 重复审核被拒(400) ✅
```

### 回归
| 项目 | 结果 |
|------|------|
| 后台 `vue-tsc` | 通过 |
| 后台 `oxlint`（212 文件） | 0 error |
| 后端 `mvn test` | 16/16 通过 |
| 数据库一致性校验 | 3/3 通过 |
| 小程序 `npm run check` | 22 项通过 |
| 分账快照一致性 | **9/9** |
| 商品规格未被污染 | 106 条 ✅ |

## 七、改动文件

**后端新增**
- `db/migration/V9__spec_template_and_split_seed.sql`
- `marketing/controller/AdminMarketingConfigController.java`

**后端修改**
- `system/crud/CrudRegistry.java`（资源 7 → 21）

**前端新增**
- `src/service/api/crud.ts` 追加 6 个特殊动作函数

**前端修改**
- `src/store/modules/admin/index.ts`（资源映射扩展 + 4 个特殊动作接接口）
- 10 个页面（remoteKey 声明 / onMounted 加载）

## 八、后台接入完成度

| 批次 | 模块 | 状态 |
|------|------|------|
| 1 | 系统配置 | ✅ 已接入 |
| 2 | 主体管理 | ✅ 已接入 |
| 3 | 商品配置 | ✅ 已接入 |
| 4 | 营销配置 | ✅ 已接入 |
| — | 授权管理（3 页） | 待接入（需角色/授权写接口） |
| — | 财务（7 页） | 待接入（多为查询，部分动作已有接口） |
| — | 审核（2 页） | 待接入（角色审核接口已就绪，页面待接） |
| — | 交易（7 页） | 部分已接（订单/核销/退款已有接口） |
