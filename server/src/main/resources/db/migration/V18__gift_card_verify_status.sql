-- =============================================================
-- 礼品卡订单完整流程字段（门店扫码核销闭环）
-- 来源：用户需求「礼品卡订单后续门店扫码核销走流程」
--
-- 对齐门店订单(orders)的状态机，gift_card_order 补齐：
--   status(订单状态) / cancel_type(取消类型) / refund_amount(退款金额)
--   pay_time(支付时间) / verify_status(核销状态) / verify_time(核销时间)
-- =============================================================

ALTER TABLE gift_card_order
    ADD COLUMN status         VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT '订单状态 CREATED/PAID/CANCELED/VERIFIED' AFTER pay_status,
    ADD COLUMN cancel_type    VARCHAR(16) NULL COMMENT '取消类型 pending/paid' AFTER status,
    ADD COLUMN refund_amount  BIGINT      NOT NULL DEFAULT 0 COMMENT '退款金额(分)' AFTER cancel_type,
    ADD COLUMN pay_time       DATETIME    NULL COMMENT '支付时间' AFTER refund_amount,
    ADD COLUMN verify_status  VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED' COMMENT '核销状态 UNVERIFIED/VERIFIED' AFTER pay_time,
    ADD COLUMN verify_time    DATETIME    NULL COMMENT '核销时间' AFTER verify_status;

-- 历史已支付订单：订单状态=PAID，支付时间=创建时间，保持待核销
UPDATE gift_card_order
SET status = 'PAID', pay_time = COALESCE(pay_time, create_time)
WHERE pay_status = 'PAID' AND status = 'CREATED';
