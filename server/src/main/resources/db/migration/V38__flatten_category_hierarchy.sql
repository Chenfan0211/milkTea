-- =============================================================
-- 分类层级拍平：去掉 TAB/GROUP/CATEGORY 三级，统一为单层「分类」
--
-- 决策（用户确认）：
--   1. 旧 TAB/GROUP 数据物理删除；
--   2. 菜单结构完全单层（无 Tab、无分组，仅一个分类列表）；
--   3. 「标签」= 每个分类自身 tag（小程序端分类左上角角标）。
--
-- 部署前核对结论（2026-09-25 生产库 wuling）：
--   - category_id 为 NULL 的商品：0 条
--   - category_id 指向 TAB/GROUP 的商品：0 条
--   - category_id 悬空引用的商品：0 条
--   => 数据干净，可安全执行下方 NOT NULL 变更。
--
-- 影响面：
--   - product_category 仅保留 CATEGORY 行；TAB/GROUP 行物理删除。
--   - 分组级 tag 下沉到其直属分类（避免左上角标签信息丢失）。
--   - 清理测试验证遗留的垃圾分类数据（code 以 '__' 前缀、enabled=0）。
--   - product.category_id 由 NULL 改为 NOT NULL，保证「商品必绑分类」。
-- =============================================================

-- 1) 分组级 tag 下沉到其直属分类（原分组 tag 有值时，分类 tag 为空则继承）
UPDATE product_category c
JOIN product_category g
  ON g.id = c.parent_id AND g.type = 'GROUP'
   SET c.tag = COALESCE(NULLIF(c.tag, ''), g.tag);

-- 2) 清理测试验证遗留的垃圾分类数据（code 以 '__' 前缀、enabled=0）
--
-- 【为什么不用 LIKE + ESCAPE】
--   原写法 `code LIKE '\_\_%' ESCAPE '\'` 是无法执行的：ESCAPE 的参数 '\' 以反斜杠收尾，
--   MySQL 解析器会认定「反斜杠转义了结束引号」，于是字符串未闭合 -> 直接语法错误。
--   更麻烦的是它还会带偏 Flyway 的分号切分器：解析器以为字符串没结束，就把后续的
--   第 3/4/5 条语句全吞成一条，最终报错停在 `near 'TAB', 'GROUP')` ——
--   症状指向第 3 条，病根却在第 2 条，排查方向极易被误导。
--
--   因此改用字符串函数 LEFT()：语义直白、彻底绕开转义符坑；
--   且 product_category 基数极小（十几行），不依赖索引，无性能顾虑。
DELETE FROM product_category
 WHERE LEFT(code, 2) = '__' AND enabled = 0;

-- 3) 物理删除旧层级节点（TAB / GROUP）
DELETE FROM product_category WHERE type IN ('TAB', 'GROUP');

-- 4) 剩余分类统一 type = 'CATEGORY'（历史数据可能已有小写/空格，归一化）
UPDATE product_category SET type = 'CATEGORY' WHERE UPPER(TRIM(type)) = 'CATEGORY';

-- 5) 商品分类改必填
ALTER TABLE product
    MODIFY category_id BIGINT UNSIGNED NOT NULL COMMENT '所属分类 id（必填）';
