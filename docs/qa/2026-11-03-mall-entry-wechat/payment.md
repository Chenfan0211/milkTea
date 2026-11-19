# 商城微信支付与建单验证记录

日期：2026-11-03。仅修改本地代码与测试；未提交 Git，未操作线上，未真实下单或付款。

## 已修复的事务故障

`MallOrderService.create(userId, request)` 两参数外部入口增加事务，修复其内部调用三参数方法绕过代理的问题。库存预占失败时，订单和明细随本地事务回滚；预占成功但本地后续回滚时保留既有库存释放补偿。

`PaymentService.routeWxPayCallback` 成为外部可拦截的事务入口。订单 PAID、库存幂等确认、Payment 更新均成功后才提交。历史 PAID 订单的重推仍补齐库存确认及支付记录；订阅/支付事件不因重复通知重复发布。退款终态不回退。

验证使用真实 `OrderService`、`PaymentService`、`MallOrderService`，通过 Spring CGLIB + `TransactionInterceptor` 调用公共入口，使用内存 Mapper 与事务快照模拟数据库提交/回滚，并注入产品库存、Payment 持久化故障。不是反射检查注解。

初始 RED 明确观察到四项行为失败：库存确认失败后订单仍 PAID；Payment 保存失败后订单仍 PAID；历史 PAID 跳过库存修复；外部两参数建单失败留下订单/明细。修复后这些场景均通过。

## 支付事实与补偿

- 可信微信查单保留订单号、交易号、金额、币种、商户号、AppID、付款人信息；微信网关校验订单号、商户/AppID、CNY，入账再次核对金额与交易标识。回调从 SDK 提取并验证币种。
- 手工失败重试、30 秒 PAYING 扫描与回调共用入账逻辑，查单 SUCCESS 会更新订单、库存及 Payment，不能只把 Payment 改为 PAID。未知/网络不明保持原状态。
- Payment 保存实际通道；原单再次预支付、主动查单及退款都按该通道解析，不能跟随全局通道切换。
- 重复预支付复用原 Payment 和通道，刷新同一商户订单号的收银台参数。历史多次尝试只保留一笔 canonical PAID，其余尚未付的尝试置 CLOSED/SUPERSEDED，保留审计记录，避免重复统计收款。
- 补偿扫描使用 ID 游标分批、走到底后回到起点，历史 unknown 不会始终占据首批并饿死后续订单。行为测试使用每批 2 条、前面 3 条 unknown、后面真实待入账订单，验证第二轮成功入账且 unknown 未被关闭。

调度配置：`app.pay.reconcile-enabled=true`、`app.pay.reconcile-delay-ms=30000`、`app.pay.reconcile-batch-size=200` 为默认值。

## 取消、超时与迟到支付

- 完全没有预支付记录的新单可以本地取消或超时关闭并释放库存。
- 已有原支付记录的商城订单，取消/超时先按原通道查单。SUCCESS 先入账并返回已支付；unknown 保留待付和库存；确认未付后必须微信关单成功才本地取消和释放。
- 预支付 `time_expire` 与订单创建时间 +15 分钟一致；本地已超时不再发起预支付。
- 预支付保存失败会从外部事务代理抛异常，调用方拿不到支付参数。远端可能留下的未付款预支付单由相同 `time_expire` 限制；本次未引入独立事务支付意图。
- 关闭订单若收到合法迟到 SUCCESS，记录 Payment 已收款事实、触发人工核查/退款告警，订单仍 CANCELED，不确认库存、不发货。

## 退款网关边界

新增五参数 `refund(orderNo, refundNo, refundFen, originalTotalFen, reason)`，微信请求分别使用退款额和原实付总额。旧四参数保留全额退款委托。

`RefundApplyResult` 增加 `unknown`，保留原三参数构造。网络超时、一般 SDK/验签异常、5xx、SDK 空响应或响应缺少状态等返回 `accepted=false, unknown=true`，不能伪称已确认受理或明确失败；退款服务保留原退款号查证。可信 4xx 明确业务拒绝可返回确定失败，408/409/429 保守视为 unknown。退款本地编排验证见 `refund.md`。

## 验证结果

新增 `MallPaymentTransactionTest` 19 项、`WechatPayGatewaySafetyTest` 6 项全部通过，覆盖上述事务、查单、取消、迟到支付、重复意图、跨批扫描、微信 SDK 关单/退款金额与 unknown 边界。相关旧支付、商城订单、微信状态/配置/验签、超时扫描回归集合通过。

执行命令：

```powershell
mvn -pl trade-service -am test '-Dtest=*Payment*Test,*WxPay*Test,*MallOrder*Test,OrderTimeoutSweepJobTest,WechatPayGateway*Test' '-Dsurefire.failIfNoSpecifiedTests=false' -q
```

该命令退出码 0；上述新增两类 Surefire 报告分别为 19/0/0 与 6/0/0（tests/failures/errors）。初始故障测试、批次饥饿与退款 unknown 均先观察到预期失败再修复。完整多模块打包由主任务统一执行。

这些测试没有连接微信或真实数据库。真实支付回调、线上迁移/数据库 SQL 执行及用户授权的一元内实付验收，由主任务另行核实和记录。

## 共享退款协议兼容复验

末轮审查发现礼品卡消费者 `GiftCardRefundService` 尚未处理新增 `unknown`，会把未知返回或空返回当明确失败、恢复订单/Payment 为 PAID。此处仅补共享协议兼容：unknown/null 保持 REFUNDING 并告警，按原退款号查询；正常受理与明确拒绝的既有行为保留。

新增两项业务回归以可变订单和 Payment 模拟营销/持久化边界，初始均观察到 REFUNDING 被错误恢复为 PAID（RED）。最小修复后运行下列命令，13 项通过、0 失败、0 错误，退出码 0：

```powershell
mvn -pl trade-service -am test '-Dtest=GiftCardRefundServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' -q
```

本项等待前一轮全仓构建结束释放源码/Maven 锁后才修改，随后将锁交主任务执行最终全仓复验。
