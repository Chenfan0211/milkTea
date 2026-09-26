-- =============================================================
-- V44：余额支付意图表（跨服务「扣款-落库」补偿对账用）
--
-- 背景：余额支付在 trade 域一次事务里完成
--   「调 marketing 扣余额 -> 置订单已支付 -> 写支付单」。
-- 扣余额发生在 marketing 库（远程、独立事务、立即提交），
-- 而订单落库在 trade 库（本地事务）。若 trade 本地事务在扣款成功后
-- 回滚（markPaid / 写支付单 抛异常），就会留下「已扣款但订单未支付」的悬挂单：
--   用户钱被扣了，订单却还是待支付，且没有支付记录可追。
--
-- 因此这里在「扣款前」用一个独立事务先落一条「支付意图」，
-- 即便主事务回滚，意图记录仍在（REQUIRES_NEW 独立提交）。
-- 补偿任务据此扫悬挂单并回冲余额，保证资金最终一致。
--
-- 幂等键：order_no —— 一张订单同一时间只允许一笔未完结的余额支付意图。
-- =============================================================

CREATE TABLE IF NOT EXISTS balance_pay_intent (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(64) NOT NULL COMMENT '订单号（幂等键）',
    user_id       BIGINT UNSIGNED NOT NULL COMMENT '支付用户',
    amount        BIGINT NOT NULL DEFAULT 0 COMMENT '扣款金额（分）',
    status        VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=扣款已发起待确认 / DONE=订单已支付 / COMPENSATED=已回冲',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_balance_pay_intent_order (order_no),
    KEY idx_balance_pay_intent_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='余额支付意图（扣款-落库补偿对账）';