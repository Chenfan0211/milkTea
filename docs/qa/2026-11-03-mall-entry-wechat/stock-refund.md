# 商城退款库存回补契约修复

日期：2026-11-03。此记录覆盖本地修复和回归验证；线上恢复结果应由部署后的验收继续记录。

## 真实问题与根因

首单 `MO20261103110326170` 的 1 分支付已由微信确认，库存已有 `ORDER / 原订单号` 的 `RESERVE` 和 `DEDUCT`。退款 `RF75118577913` 已被微信受理并产生第三方退款号。同步微信成功结果时，商品服务返回“订单尚未支付，不能回补库存”，使交易服务本地事务回滚，仍显示 `REFUNDING`。

源码有两个相互叠加的问题：

- `RemoteMallCatalogAdapter.restore(orderNo, lines)` 委托售后三参方法，把整单退款也发送成 `AFTER_SALE / orderNo`。
- `MallInventoryService.restore` 使用本次退款的 `bizType / bizNo` 查找 `DEDUCT`；真实扣减流水的身份始终为 `ORDER / 原订单号`。部分退款也因此无法找到付款凭证。

本次没有访问线上服务、重复申请退款、修改数据库资金状态或提交代码；全部既有工作区改动均保留。

## 修复行为

| 操作     | 回补幂等身份                         | 付款凭证                   |
| -------- | ------------------------------------ | -------------------------- |
| 整单退款 | `ORDER / orderNo / RESTORE`          | `ORDER / orderNo / DEDUCT` |
| 售后退款 | `AFTER_SALE / afterSaleNo / RESTORE` | `ORDER / orderNo / DEDUCT` |

商品服务仍按 SKU 升序加锁，软删除 SKU 继续通过 `IncludingDeleted` 回补。加锁后新增 `selectRefundChangesForUpdate(orderNo)`，用 `FOR UPDATE` 当前读原订单的 `DEDUCT / RESTORE`，避免 MySQL RR 非锁定快照遗漏其他事务刚提交的回补。

当前流水中已存在同一业务幂等身份时先返回成功，保证“商品服务已提交、交易服务本地回滚”的同号重放可以恢复。否则先完整验证全部 SKU：只累计 `ORDER / 原订单号` 的原始扣减，减去该订单所有业务身份已完成的回补；未购买 SKU、跨不同售后单累计超量、整单与售后重复计算数量均被拒绝，且拒绝前不变更任何 SKU。

## 测试方式与证据

新增 `RemoteMallCatalogInventoryContractTest` 使用真实 `RemoteMallCatalogAdapter`、HTTP 请求/响应 JSON、真实 `MallStockInternalController` 与真实 `MallInventoryService`。`MockMvcClientHttpRequestFactory` 把请求交给真实控制器；只有 mapper 使用有状态内存账本，库存结果由真实服务计算，没有 mock 库存成功响应。

9 条契约用例覆盖：整单回补及重复请求；两张不同售后单分次回补及分别重试；只有预占未支付时拒绝；累计回补超量拒绝；整单与部分退款共享累计额度；未购 SKU 拒绝；多 SKU 先完整校验再修改；软删除 SKU 可回补；非锁定查询快照遗漏已提交流水时仍按锁后当前读幂等。

| 阶段                           | 结果                                                            | 日志                           |
| ------------------------------ | --------------------------------------------------------------- | ------------------------------ |
| 原实现 RED                     | 8 项，5 失败，0 异常，复现整单与售后退款被拒绝                  | `stock-refund-red.log`         |
| 仅修业务身份与付款凭证后的 RED | 9 项，5 失败，0 异常，暴露超量、未购 SKU、多 SKU 与快照重放问题 | `stock-refund-limit-red.log`   |
| 完整修复 GREEN                 | 商品服务 11 项，交易服务 68 项，共 79 项全部通过                | `stock-refund-green.log`       |
| 构建契约 RED                   | 默认依赖商品服务 Boot 主包时，`test package` 的交易测试编译失败 | `stock-refund-package-red.log` |
| 修正测试构建后                 | `test package` 成功，79 项全部通过                              | `stock-refund-package.log`     |
| 最终单命令复核                 | `test` 成功，79 项全部通过                                      | `stock-refund-test.log`        |

为确保同时支持 `test` 与 `package`，商品服务在 `process-classes` 阶段额外附加普通 `contract` classifier jar，交易服务仅以 `test` scope 依赖它。已核验商品/交易主 jar 仍保留原文件名与 `BOOT-INF/classes` 可执行布局，交易主 jar 没有携带商品服务运行时依赖。

定向回归命令（均不执行 `clean`）：

```powershell
mvn -pl product-service,trade-service -am '-Dtest=MallInventoryServiceTest,RemoteMallCatalogInventoryContractTest,MallStockServiceTest,MallOrderServiceTest,MallAfterSaleServiceTest,RefundServiceAfterSaleTest,RefundServiceStatusTest,RefundServiceSafetyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -pl product-service,trade-service -am '-Dtest=MallInventoryServiceTest,RemoteMallCatalogInventoryContractTest,MallStockServiceTest,MallOrderServiceTest,MallAfterSaleServiceTest,RefundServiceAfterSaleTest,RefundServiceStatusTest,RefundServiceSafetyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test package
```

契约测试未连接真实 MySQL；并发快照风险通过有状态账本模拟，当前读 SQL 与锁顺序经独立代码审查。本地修复不能证明线上状态已经恢复。

## 后续恢复边界

独立审查后由主代理完成全量 `package`，重新部署 `product-service` 与 `trade-service`。只使用原退款号 `RF75118577913` 查询/同步微信退款结果，核验本地退款和订单终态及原订单库存只回补一次。不得再次申请退款，不得直接更新数据库退款/支付状态。
