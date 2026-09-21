-- =============================================================
-- 商品 → 供应商归属建模（P1/P2 遗留项补全）
-- 约定：每个上架商品必须绑定一个供应商（分账②B：未绑定则拒绝下单/分账）
-- =============================================================

ALTER TABLE product
    ADD COLUMN supplier_subject_id BIGINT UNSIGNED NULL COMMENT '供应商主体ID（分账供应商份额归属）' AFTER split_rule_id,
    ADD KEY idx_product_supplier (supplier_subject_id);

-- 供应商主体档案补充商品归属统计口径说明
ALTER TABLE supplier_profile
    MODIFY COLUMN product_count INT NOT NULL DEFAULT 0 COMMENT '在售商品数（由 product.supplier_subject_id 统计）';

-- 种子：为 18 个商品分配供应商（401/402/403 轮转，保证全部有归属）
UPDATE product SET supplier_subject_id = 401 WHERE id IN (1, 2, 3, 4, 5, 6);
UPDATE product SET supplier_subject_id = 402 WHERE id IN (7, 8, 9, 10, 11, 12);
UPDATE product SET supplier_subject_id = 403 WHERE id IN (13, 14, 15, 16, 17, 18);

-- 按实际归属回填供应商商品数
UPDATE supplier_profile sp
SET sp.product_count = (SELECT COUNT(*) FROM product p
                        WHERE p.supplier_subject_id = sp.subject_id AND p.deleted = 0 AND p.on_sale = 1);
