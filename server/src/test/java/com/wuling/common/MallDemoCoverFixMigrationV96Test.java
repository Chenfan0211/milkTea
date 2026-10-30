package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MallDemoCoverFixMigrationV96Test {

    private static final Path MIGRATION =
            Paths.get("src/main/resources/db/migration/V96__mall_demo_cover_fix.sql");

    private static final Path V95_MIGRATION =
            Paths.get("src/main/resources/db/migration/V95__mall_demo_seed.sql");

    private static final String V95_NORMALIZED_SHA256 =
            "21C2F54A6327D48FB90595BE6EAB4F877609CC2BEEA596390B2AB8EEA5AA08F2";

    private static final String[][] EXPECTED_COVER_MAPPINGS = {
            {"DEMO-MALL-001", "/assets/images/3x/menu-product.jpg"},
            {"DEMO-MALL-002", "/assets/images/3x/tea-dahongpao.jpg"},
            {"DEMO-MALL-003", "/assets/images/3x/tea-yunling-yimi.jpg"},
            {"DEMO-MALL-004", "/assets/images/3x/gift-card-matcha.jpg"},
            {"DEMO-MALL-005", "/assets/images/3x/gift-card-jasmine.jpg"},
            {"DEMO-MALL-006", "/assets/images/3x/points-product-pet.jpg"},
            {"DEMO-MALL-007", "/assets/images/3x/gift-card-limited.jpg"},
            {"DEMO-MALL-008", "/assets/images/3x/tea-chunyun-xiaozhong.jpg"}
    };

    private static final String[][] EXPECTED_CATEGORY_MAPPINGS = {
            {"tea-goods", "/assets/images/3x/mall-category-tea-goods.jpg"},
            {"living-goods", "/assets/images/3x/mall-category-living-goods.jpg"},
            {"gift-box", "/assets/images/3x/mall-category-gift-box.jpg"}
    };

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "V96 迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void mapsExactlyEightDemoProductCodesToTheirExpectedCoverImages() throws IOException {
        String sql = sql();
        Map<String, String> expected = mappingMap(EXPECTED_COVER_MAPPINGS);
        Map<String, String> actual = parseCoverMappings(stripSqlComments(sql));

        assertEquals(expected.size(), actual.size(), "V96 必须且只能包含 8 条演示商品图片映射");
        assertEquals(expected, actual, "演示商品编码与封面图片必须逐条一一对应");
        assertTrue(Pattern.compile("(?is)UPDATE\\s+mall_product\\s+product\\s+JOIN\\s*\\("
                        + ".*\\)\\s+AS\\s+seed\\s+ON\\s+seed\\.product_code\\s*=\\s*product\\.product_code\\s+SET")
                        .matcher(sql).find(),
                "商品封面必须通过固定映射表 UPDATE ... JOIN 修正");
    }

    @Test
    void setsGalleryImagesFromTheSameMappedCoverImage() throws IOException {
        String sql = stripSqlComments(sql());
        assertTrue(Pattern.compile("(?is)SET\\s+product\\.cover_image\\s*=\\s*seed\\.cover_image\\s*,"
                        + "\\s*product\\.gallery_images\\s*=\\s*JSON_ARRAY\\(\\s*seed\\.cover_image\\s*\\)")
                        .matcher(sql).find(),
                "图集必须使用 JSON_ARRAY(cover_image) 与映射后的商品封面保持一致");
    }

    @Test
    void syncsOnlyDemoSkuImagesFromTheirProductCover() throws IOException {
        String sql = stripSqlComments(sql());
        assertTrue(Pattern.compile("(?is)UPDATE\\s+mall_sku\\s+sku\\s+JOIN\\s+mall_product\\s+product\\s+"
                        + "ON\\s+product\\.id\\s*=\\s*sku\\.product_id"
                        + "(?:\\s+AND\\s+product\\.deleted\\s*=\\s*0)?\\s+"
                        + "SET\\s+sku\\.image\\s*=\\s*product\\.cover_image\\s+"
                        + "WHERE\\s+product\\.product_code\\s+LIKE\\s+'DEMO-MALL-%'\\s*;")
                        .matcher(sql).find(),
                "SKU 图必须通过 JOIN mall_product 取 cover_image，且仅限 DEMO-MALL-* 商品");
    }

    @Test
    void fixesExactlyThreeCategoryImagesWithTheExpectedMapping() throws IOException {
        String sql = sql();
        Map<String, String> expected = mappingMap(EXPECTED_CATEGORY_MAPPINGS);
        Map<String, String> actual = parseCategoryMappings(stripSqlComments(sql));

        assertEquals(expected.size(), actual.size(), "V96 必须且只能修正 3 个演示分类图片");
        assertEquals(expected, actual, "分类编码与图片必须逐条一一对应");
        assertTrue(Pattern.compile("(?is)UPDATE\\s+mall_category\\s+SET\\s+icon_image\\s*=\\s*CASE\\s+code"
                        + ".*WHERE\\s+code\\s+IN\\s*\\(\\s*'tea-goods'\\s*,\\s*'living-goods'\\s*,\\s*'gift-box'\\s*\\)\\s*;")
                        .matcher(sql).find(),
                "分类图片必须限定为三个演示分类");
    }

    @Test
    void containsOnlyThreeIdempotentUpdateStatementsAndNoInserts() throws IOException {
        String executableSql = stripSqlComments(sql());
        assertFalse(Pattern.compile("\\bINSERT\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(executableSql).find(),
                "V96 只做图片修正，禁止任何 INSERT");

        List<String> statements = new ArrayList<>();
        for (String statement : executableSql.split(";")) {
            if (!statement.trim().isEmpty()) {
                statements.add(statement.trim());
            }
        }
        assertEquals(3, statements.size(), "V96 只能包含商品、SKU、分类三条 UPDATE 语句");
        for (String statement : statements) {
            assertTrue(statement.toUpperCase(Locale.ROOT).startsWith("UPDATE "),
                    "V96 中的每条可执行语句都必须是 UPDATE: " + statement);
        }
    }

    @Test
    void assignsOnlyImageFields() throws IOException {
        Set<String> assignmentTargets = parseAssignmentTargets(stripSqlComments(sql()));
        Set<String> expectedTargets = new LinkedHashSet<>(Arrays.asList(
                "product.cover_image", "product.gallery_images", "sku.image", "icon_image"));

        assertEquals(expectedTargets, assignmentTargets, "V96 只能修改商品图集、封面、SKU 图和分类图字段");
        for (String forbidden : Arrays.asList(
                "sale_price", "original_price", "cost_price", "platform_commission",
                "stock", "locked_stock", "sort", "status", "sales_count", "deleted", "category_id")) {
            assertFalse(assignmentTargets.contains(forbidden), "不得修改字段: " + forbidden);
        }
    }

    @Test
    void v95MigrationFileRemainsUnchangedBySha256Fingerprint() throws IOException {
        assertTrue(Files.exists(V95_MIGRATION), "V95 迁移脚本必须存在");
        String normalizedV95 = Files.readString(V95_MIGRATION, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        assertEquals(V95_NORMALIZED_SHA256, sha256(normalizedV95),
                "禁止修改已上线 V95 文件");
    }

    /**
     * V96 引用的每个图片路径都必须是小程序端真实存在的 3x 素材。
     *
     * <p>为什么需要这条：迁移脚本写的是字符串路径，Java 编译与前端测试都不会
     * 去解析它。一旦路径拼错或素材被误删，只有线上点开商品才会发现图片 404，
     * 而那时已经部署完成。这里把「脚本引用的路径」与「仓库内真实文件」对齐。
     */
    @Test
    void everyReferencedImagePathExistsInMiniprogramAssets() throws IOException {
        Set<String> referenced = new LinkedHashSet<>();
        for (Matcher matcher = Pattern.compile("'(/assets/images/[^']+)'")
                .matcher(stripSqlComments(sql())); matcher.find(); ) {
            referenced.add(matcher.group(1));
        }
        assertFalse(referenced.isEmpty(), "V96 必须引用至少一个图片路径");

        Path assetsRoot = Paths.get("../user-h5");
        assertTrue(Files.isDirectory(assetsRoot),
                "未找到小程序工程目录，无法校验素材: " + assetsRoot.toAbsolutePath().normalize());

        List<String> missing = new ArrayList<>();
        for (String assetPath : referenced) {
            // '/assets/images/3x/x.jpg' 对应 user-h5/assets/images/3x/x.jpg
            Path file = assetsRoot.resolve(assetPath.replaceFirst("^/", ""));
            if (!Files.isRegularFile(file)) {
                missing.add(assetPath);
            }
        }
        assertTrue(missing.isEmpty(),
                "V96 引用了小程序端不存在的素材，线上会 404：\n\n  - "
                        + String.join("\n  - ", missing));
    }

    private static Map<String, String> mappingMap(String[][] mappings) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String[] mapping : mappings) {
            result.put(mapping[0], mapping[1]);
        }
        return result;
    }

    private static Map<String, String> parseCoverMappings(String executableSql) {
        Pattern mappingPattern = Pattern.compile(
                "SELECT\\s+'([^']+)'\\s+AS\\s+product_code\\s*,\\s*'([^']+)'\\s+AS\\s+cover_image"
                        + "|UNION\\s+ALL\\s+SELECT\\s+'([^']+)'\\s*,\\s*'([^']+)'",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = mappingPattern.matcher(executableSql);
        Map<String, String> mappings = new LinkedHashMap<>();
        while (matcher.find()) {
            boolean firstForm = matcher.group(1) != null;
            String productCode = firstForm ? matcher.group(1) : matcher.group(3);
            String coverImage = firstForm ? matcher.group(2) : matcher.group(4);
            mappings.put(productCode, coverImage);
        }
        return mappings;
    }

    private static Map<String, String> parseCategoryMappings(String executableSql) {
        Pattern mappingPattern = Pattern.compile(
                "WHEN\\s+'([^']+)'\\s+THEN\\s+'([^']+)'", Pattern.CASE_INSENSITIVE);
        Matcher matcher = mappingPattern.matcher(executableSql);
        Map<String, String> mappings = new LinkedHashMap<>();
        while (matcher.find()) {
            mappings.put(matcher.group(1), matcher.group(2));
        }
        return mappings;
    }

    private static Set<String> parseAssignmentTargets(String executableSql) {
        Pattern setClausePattern = Pattern.compile("(?is)\\bSET\\s+([^;]+?)(?=\\bWHERE\\b|;)");
        Matcher setClauseMatcher = setClausePattern.matcher(executableSql);
        Set<String> targets = new LinkedHashSet<>();
        while (setClauseMatcher.find()) {
            for (String assignment : setClauseMatcher.group(1).split(",")) {
                String[] parts = assignment.split("=", 2);
                assertTrue(parts.length == 2, "SET 子句必须包含合法赋值: " + assignment);
                targets.add(parts[0].trim().toLowerCase(Locale.ROOT));
            }
        }
        return targets;
    }

    private static String stripSqlComments(String sql) {
        return sql.replaceAll("(?m)--[^\\r\\n]*", "");
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02X", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }
}
