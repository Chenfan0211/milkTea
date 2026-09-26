-- ---------- 退款三态收敛（退款管理改造） ----------
-- 目标状态：REFUNDING(退款中) / FAILED(退款失败) / SUCCESS(退款成功)
-- 去掉历史遗留的 APPLIED / APPROVED / REJECTED / PENDING 等审核态。

-- 1) refund 表新增微信退款单号列（受理成功后回填，用于对账/查询）
ALTER TABLE refund
    ADD COLUMN third_refund_no VARCHAR(64) NULL COMMENT '微信退款单号' AFTER reason;

-- 2) 收敛历史状态到新三态
UPDATE refund SET status = 'SUCCESS'   WHERE status IN ('SUCCESS', 'APPROVED', 'COMPLETED');
UPDATE refund SET status = 'FAILED'    WHERE status IN ('REJECTED', 'FAIL');
UPDATE refund SET status = 'REFUNDING' WHERE status IN ('APPLIED', 'PENDING', 'PROCESSING');