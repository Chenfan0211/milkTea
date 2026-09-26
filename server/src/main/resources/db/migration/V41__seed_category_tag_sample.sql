-- =============================================================
-- V41：为分类补一个示例「标签」（category-left-top badge）
--
-- 背景：
--   product_category.tag 由 V30 建列、V38 做过「分组 tag 下沉到分类」，
--   但 V4 种子只写了 (id, parent_id, code, name, type, sort)，从未写过 tag，
--   且原分组 tag 本身即为空，所以下沉后全部分类的 tag 仍为 NULL。
--   小程序点单页 menu.wxml 有 `wx:if="{{category.tag}}"` 的左上角角标渲染，
--   取值为空 -> 整块不渲染，表现为「分类没有标签」。
--
-- 本迁移只补 1 条示例，用于验证「后台写 tag -> 接口下发 -> 小程序显示」链路，
-- 其余分类的标签后续在管理后台「分类管理」页维护
-- （tag 已在 CrudRegistry.productCategories 的写入白名单中，可直接保存）。
--
-- 幂等：仅当 tag 为空时才写入，不覆盖后台已手工配置的标签。
-- =============================================================

UPDATE product_category
   SET tag = '上新'
 WHERE code = 'featured-season'
   AND (tag IS NULL OR tag = '');