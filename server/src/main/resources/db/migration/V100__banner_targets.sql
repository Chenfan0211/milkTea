-- 我的页现有轮播默认继续跳转点单页；新轮播可配置具体功能或商品。
ALTER TABLE profile_banner
    ADD COLUMN target_type VARCHAR(32) NOT NULL DEFAULT 'menu' AFTER alt_text,
    ADD COLUMN target_id VARCHAR(64) NOT NULL DEFAULT '' AFTER target_type;
