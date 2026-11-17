# 提现统一运营审核：发布记录

发布于 2026-11-17（北京时间）。所有金额的提现申请统一先冻结余额、生成待审核单；运营后台通过后才发起出款，驳回或失败则解冻。

## 变更与发布

- 服务端镜像：`wuling/server:20261117-withdraw-review-110954`，JAR SHA256：`2badad05ee86bd58997d8e9e6f1c73fe05b90db420e61d4ab7810f607791a459`。原镜像：`wuling/server:20261117-mall-leader-094620`。
- 数据库迁移 V107：原有 3 个非零主体免审阈值清零；`withdraw_rule` 配置改为所有金额须审核，保留费用和失败说明。Redis DB 2 中的 `wuling:cache:app_config:withdraw_rule` 已定向失效。
- 运营后台静态站已发布，发布前目录：`/opt/wuling/web.old-20261117-111255`，归档：`/opt/wuling/backup/web-pre-20261117-111255.tar.gz`。
- 发布前备份：`/opt/wuling/backup/20261117-withdraw-review-110954/`，包含旧 JAR、旧构建包、全库备份；数据库备份 SHA256：`6381330d4227689c494374560f9a411e0ed6a2fd617612d2b7a8d6b1111041d1`，gzip 校验通过。
- 小程序规则页与提现相关文案已修改并通过本地检查；微信小程序公开版本未在本次发布。

## 验证

- 服务端 `WithdrawalServiceTest`、`WithdrawalControllerRuleTest` 通过；覆盖 1 分小额待审核、大额待审核、审核通过出款、驳回解冻、通道受理/失败。
- 运营后台 `pnpm typecheck`、`pnpm build` 和相关脚本检查通过；小程序 `npm run check`、`node scripts/check-project.mjs` 通过。
- 线上服务容器 `healthy`，`/actuator/health` 为 `UP`。Flyway 最新版本 107 成功，失败迁移 0，非零免审阈值 0。
- 公网 `withdraw_rule` 返回 `instantLimit=¥0.00` 和全额运营审核说明；运营后台 `index.html` 的本地、服务器和公网 SHA256 一致，提现及配置资源返回 200。
- 未授权申请与审核请求均返回 401。未在生产环境发起真实提现或出款。

## 发布边界

服务端已经对所有新申请强制审核。现有微信小程序公开版本可能仍显示旧的规则文案；需要单独发布小程序新版本后，用户界面的说明才会同步。小程序工作区还包含其他尚未发布的功能变更，发布前应一起核对版本范围。
