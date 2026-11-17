# 商城团长归属与运营后台整合实施计划

**目标：**落实 `docs/调整方案-商城团长归属与运营后台整合.md`，让分享归属、订单团长快照、商城订单分账及财务展示闭合。

**架构：**服务端以 `user_leader` 与团长授权为归属权威；小程序分享参数只用于申请绑定。商城下单固化团长 ID 和归属类型，完成与退款按订单快照入账。管理端读取订单与财务的真实快照。

**技术栈：**Spring Boot、MyBatis Plus、MySQL/Flyway；Vue 3、TypeScript、Naive UI；微信小程序 JavaScript。

**依据：**`docs/调整方案-商城团长归属与运营后台整合.md`。

## 全局约束

- 不改动工作区里已有的无关未提交文件，不重写历史订单和账务。
- 分享自动绑定首次有效关系优先；后台人工改绑可覆盖。团长本人买单只享团长价，团长佣金为 0。
- 无团长的新商城订单保存 `PLATFORM` 归属类型、团长 ID 为 `NULL`；历史未知归属保持未知。
- 金额单位在数据库/API 内为分；页面转元，合计以账务快照和冲正记录为准。
- 小程序页面/组件遵循 `user-h5/docs/design-system.md`，修改后执行 `npm run check` 与图标检查。

## Task 1：分享自动归属与有效性

**文件：**`server/src/main/java/com/wuling/subject/controller/AdminLeaderController.java`、`server/src/main/java/com/wuling/common/internal/InternalQueryController.java`，新增登录态 app 绑定入口及相应测试；下一版 Flyway 迁移补 `user_leader` 绑定来源。

- [ ] 先写服务端测试：新绑定、重复绑定、不同团长链接不覆盖、后台改绑、停用团长拒绝、并发幂等、已失效关系重绑。
- [ ] 实现从登录态取得用户 ID 的分享绑定接口，验证团长授权及主体有效；自动绑定与后台改绑共用校验，旧订单不受关系变动影响。
- [ ] 团长停用保留主体和账户历史，阻止新绑定/新订单归属；服务端读取有效归属时校验授权和主体状态。
- [ ] 跑目标服务端测试，确认失败先于实现、实现后通过。

## Task 2：小程序分享与绑定闭环

**文件：**`user-h5/utils/share.js`、`user-h5/utils/referrer.js` 或独立团长参数模块、`user-h5/pages/launch/launch.js`、`user-h5/packageMall/pages/home/home.js`、`user-h5/packageMall/pages/product-detail/product-detail.js`、登录及结算链路，相关 `user-h5/scripts/*.test.mjs`。

- [ ] 先写分享参数和绑定重试测试：团长本人分享、归属团长的普通用户转发、无团长分享、游客登录后绑定、已登录用户直接打开、绑定失败不静默按无团长结算。
- [ ] 商城分享链接分别携带分享者 `referrerId` 与有效团长 ID；落地页和冷启动捕获团长参数，登录后调用 Task 1 接口。
- [ ] 在结算前确认待处理分享归属；仅成功或明确无效时清除暂存，临时故障保留并给用户重试入口。
- [ ] 运行相关单测、`npm run check` 和 `node user-h5/scripts/check-project.mjs`。

## Task 3：订单归属快照与分账明细接口

**文件：**`trade-service/src/main/java/com/wuling/trade/service/MallOrderService.java`、`trade-service/src/main/java/com/wuling/trade/service/OrderService.java`、`trade-service/src/main/java/com/wuling/trade/dto/OrderDTO.java`、订单实体及迁移、相关后端测试。

- [ ] 先写三种订单测试：`OTHER` 有团长佣金、`SELF` 团长价且佣金 0、`PLATFORM` 团长 ID 为空且佣金归平台；再测下单后改绑/停用不影响原订单。
- [ ] 下一版迁移增加可空订单归属类型列；创建订单时一次性写入类型与团长用户/主体 ID，后台 DTO 返回这些字段及现有分账快照。
- [ ] 为商城订单明细查询提供原分账、退款冲正和净额数据；不能按现价或当前归属重算。
- [ ] 跑目标后端测试并逐项核对金额守恒。

## Task 4：后台待核销池隔离

**文件：**`trade-service/src/main/java/com/wuling/trade/controller/AdminTradeQueryController.java`、`trade-service/src/test/java/com/wuling/trade/controller/AdminTradeQueryControllerTest.java`。

- [ ] 先写失败测试，断言订单总数 SQL 与分页 SQL 均限定 `order_type='STORE'`，商城订单号搜索结果为空。
- [ ] 为订单分支加类型条件，积分兑换分支保持原状；跑目标测试。

## Task 5：运营后台团长管理与财务角色

**文件：**新增 `src/views/subject/leader/index.vue`；`src/service/api/crud.ts`；路由、菜单元数据、语言映射与下一版菜单迁移；`src/views/finance/{pool,account,flow,withdraw}/index.vue`；`server/src/main/java/com/wuling/finance/controller/AdminFinanceQueryController.java`，相关测试。

- [ ] 先写菜单结构、团长操作和财务角色映射测试。
- [ ] 团长管理展示主体/用户 ID、状态、归属用户与账户余额，支持设为团长、人工改绑和停用；停用前显示受影响人数与待结算情况，历史财务记录保留。
- [ ] 财务角色文本、筛选和资金流水账户名称增加 `LEADER → 团长`，分账快照继续展示团长金额。
- [ ] 跑管理端类型检查、构建和目标测试。

## Task 6：商城订单展示与综合验收

**文件：**`src/views/mall/order/index.vue`、`src/service/api/mall.ts`，相关管理端测试。

- [ ] 先写订单三种归属、未完成待分账、已完成快照、部分/全额退款展示测试。
- [ ] 列表增加「分账明细」入口，详情显示团长 ID、归属类型、商品佣金、平台/供应商/团长原分账、冲正与净额；历史未知归属明确标记。
- [ ] 运行管理端类型检查、构建、后端相关测试与小程序检查；检查迁移顺序、菜单权限、无商城订单流入待核销池。
- [ ] 对真实支付、迁移和退款在目标环境单独验收；本地验证不得宣称完成这些外部环节。
