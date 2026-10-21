package com.wuling.user.tongue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.user.dto.tongue.TongueProductItem;
import com.wuling.user.dto.tongue.TongueReportGroup;
import com.wuling.user.dto.tongue.TongueReportSection;
import com.wuling.user.tongue.ProductCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TongueReportParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesSanitizedRealReportIntoFiveGroups() throws Exception {
        JsonNode report = fixture().get("report");

        List<TongueReportGroup> groups = TongueReportParser.groups(report);
        List<TongueReportSection> sections = flatten(groups);

        assertEquals(5, groups.size());
        assertEquals(List.of("概况", "饮食", "穴位", "情志", "运动"),
                groups.stream().map(TongueReportGroup::title).toList());
        assertEquals(20, sections.size(), "一级分组和推荐商品节点不计入组内章节");
        assertEquals(5, groups.get(1).products().size(), "推荐商品应进入饮食分组商品列表");

        TongueReportSection overview = find(sections, "1.1");
        assertNotNull(overview);
        assertEquals("<p>示例正文</p>", overview.content());

        TongueReportSection analysis = find(sections, "1.4");
        assertNotNull(analysis);
        assertNotNull(analysis.analysis());
        assertEquals(5, analysis.analysis().factors().size());
        assertEquals(5, analysis.analysis().features().size());

        TongueReportSection audio = find(sections, "4.2.1");
        assertNotNull(audio);
        assertEquals("AUDIO", audio.media().kind());

        TongueReportSection video = find(sections, "3.2.1");
        assertNotNull(video);
        assertEquals("VIDEO", video.media().kind());

        long descriptionNodes = sections.stream()
                .filter(section -> section.descriptions() != null && !section.descriptions().isEmpty())
                .count();
        long mediaNodes = sections.stream().filter(section -> section.media() != null).count();
        long analysisNodes = sections.stream().filter(section -> section.analysis() != null).count();
        long productDescriptionNodes = groups.get(1).products().stream()
                .filter(product -> product.reason() != null && !product.reason().isBlank())
                .count();
        long productMediaNodes = groups.get(1).products().stream()
                .filter(product -> product.media() != null)
                .count();
        assertEquals(11, descriptionNodes);
        assertEquals(5, productDescriptionNodes);
        assertEquals(16, descriptionNodes + productDescriptionNodes);
        assertEquals(9, mediaNodes);
        assertEquals(5, productMediaNodes);
        assertEquals(14, mediaNodes + productMediaNodes);
        assertEquals(1, analysisNodes);
    }

    @Test
    void mergesDetectionProductIdsWithReportReasons() throws Exception {
        List<TongueProductItem> detectionProducts = TongueReportParser.products(fixture().get("detection"));

        List<TongueProductItem> merged = TongueReportParser.mergeProducts(detectionProducts, fixture().get("report"));

        assertEquals(5, merged.size());
        assertEquals("vendor-goods-1", merged.get(0).goodsId());
        assertEquals("滇绿", merged.get(0).goodsName());
        assertTrue(merged.get(0).reason().contains("示例推荐理由"));
        assertEquals("IMAGE", merged.get(0).media().kind());
        assertTrue(merged.stream().allMatch(item -> item.goodsId() != null));
        assertTrue(merged.stream().allMatch(item -> item.reason() != null && item.reason().contains("示例推荐理由")));
    }

    @Test
    void summarizesFromOverviewWhenDetectionSummaryIsEmpty() throws Exception {
        JsonNode detection = fixture().get("detection");
        JsonNode report = fixture().get("report");

        assertEquals("", detection.get("summarize").asText());
        assertEquals("示例正文", TongueReportParser.summarize(report));
        assertFalse(TongueReportParser.hasReportContent(detection));
        assertTrue(TongueReportParser.hasReportContent(report));
        assertTrue(TongueReportParser.reportJson(report).contains("fixture-report"));
    }

    @Test
    void normalizesReportStatus() throws Exception {
        assertEquals("READY", TongueReportParser.reportStatus(objectMapper.readTree("""
                {"status":"DONE"}
                """), "PENDING"));
        assertEquals("PENDING", TongueReportParser.reportStatus(objectMapper.readTree("{}"), "PENDING"));
        assertEquals("PENDING", TongueReportParser.reportStatus(objectMapper.readTree("""
                {"reportStatus":"PROCESSING"}
                """), "READY"));
        assertEquals("FAILED", TongueReportParser.reportStatus(objectMapper.readTree("""
                {"reportStatus":"ERROR"}
                """), "PENDING"));
    }

    @Test
    @DisplayName("一级分组映射详情页 Tab 键，未知标题归入 other")
    void mapsGroupTitlesToTabKeys() throws Exception {
        List<TongueReportGroup> groups = TongueReportParser.groups(fixture().get("report"));

        assertEquals(List.of("overview", "diet", "acupoint", "emotion", "sport"),
                groups.stream().map(TongueReportGroup::tabKey).toList());
        assertEquals("other", TongueReportParser.tabKey("额外分组"));
        assertEquals("other", TongueReportParser.tabKey(null));
    }

    @Test
    @DisplayName("章节保留父指针与渲染块，支撑详情页分块渲染")
    void keepsParentIdAndBlock() throws Exception {
        List<TongueReportSection> sections = flatten(TongueReportParser.groups(fixture().get("report")));

        TongueReportSection overview = find(sections, "1.1");
        assertNotNull(overview);
        assertEquals(TongueReportSection.BLOCK_TEXT, overview.block());
        assertEquals("1", overview.parentId(), "顶层章节的父节点是一级分组");

        TongueReportSection analysis = find(sections, "1.4");
        assertNotNull(analysis);
        assertEquals(TongueReportSection.BLOCK_ANALYSIS, analysis.block());

        TongueReportSection acupoint = find(sections, "3.2.1");
        assertNotNull(acupoint);
        assertEquals(TongueReportSection.BLOCK_MEDIA_DETAIL, acupoint.block(),
                "穴位操作同时有视频与定位/手法/功效，必须走媒体+描述渲染器");
        assertEquals("3.2", acupoint.parentId());
        assertEquals(3, acupoint.descriptions().size());

        TongueReportSection music = find(sections, "4.2.1");
        assertNotNull(music);
        assertEquals(TongueReportSection.BLOCK_MEDIA_AUDIO, music.block());
        assertEquals("4.2", music.parentId());

        TongueReportSection fitness = find(sections, "5.2");
        assertNotNull(fitness);
        assertEquals(TongueReportSection.BLOCK_TITLE_ONLY, fitness.block(),
                "中医健身功法只作为父节点，内容由《八段锦》等子章节承载");

        assertNull(sections.stream().filter(section -> "推荐商品".equals(section.title())).findFirst().orElse(null),
                "推荐商品只进入商品列表，不作为章节下发");
    }

    @Test
    @DisplayName("商品卡按归一化商品名匹配，未命中保持 matched=false")
    void matchesProductCardsByNormalizedName() throws Exception {
        List<TongueProductItem> products = TongueReportParser.products(fixture().get("detection"));

        assertEquals(List.of("滇绿", "葛根枳椇茶", "金银菊花茶", "绿茶", "茉莉花茶"),
                TongueReportParser.productNames(products));

        Map<String, ProductCard> cards = Map.of(
                "滇绿", new ProductCard("leaf-001", "滇绿", "/assets/images/3x/menu-product.jpg",
                        990L, 1290L, List.of("原叶臻选"), List.of("痰湿质", "湿热质")),
                "葛根枳椇茶", new ProductCard("herbal-002", "葛根枳椇茶", null,
                        1290L, 1290L, List.of(), List.of())
        );

        List<TongueProductItem> matched = TongueReportParser.matchProducts(products, cards);

        assertTrue(matched.get(0).matched());
        assertEquals("leaf-001", matched.get(0).productId());
        assertEquals(990L, matched.get(0).price());
        assertEquals(List.of("原叶臻选"), matched.get(0).tags());
        assertEquals(List.of("痰湿质", "湿热质"), matched.get(0).suitableConstitutions(), "适宜体质必须透传给小程序卡片");
        assertTrue(matched.get(1).matched());
        assertFalse(matched.get(2).matched(), "商城没有的商品必须标记为未命中");
        assertNull(matched.get(2).productId());
        assertTrue(matched.get(2).tags().isEmpty());
        assertEquals(5, matched.size(), "未命中的商品仍要保留，由小程序决定不渲染");
    }

    @Test
    @DisplayName("正文走白名单 HTML，实体解码且剔除 script")
    void sanitizesContentHtml() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {"children":[{"id":"1","title":"饮食","children":[
                  {"id":"1.1","title":"饮食方式",
                   "content":"<p>&nbsp;宜食&nbsp;<strong>冬瓜</strong>&amp;薏米</p><script>alert(1)</script>"}
                ]}]}
                """);

        List<TongueReportGroup> groups = TongueReportParser.groups(data);
        TongueReportSection section = find(groups.get(0).sections(), "1.1");

        assertNotNull(section);
        assertFalse(section.content().contains("<script"), "script 必须整段剔除");
        assertFalse(section.content().contains("&nbsp;"), "&nbsp; 必须解码，不能以文字形式下发");
        assertTrue(section.content().contains("<strong>冬瓜</strong>"), "白名单标签必须保留");
        assertTrue(section.content().contains("&amp;薏米"), "裸 & 必须转义为 &amp; 后再交给 rich-text");
        assertTrue(section.content().contains("宜食"), "正文文字必须保留");
    }

    @Test
    @DisplayName("描述与推荐理由转纯文本：无标签、无实体")
    void descriptionsBecomePlainText() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {"children":[{"id":"2","title":"饮食","children":[
                  {"id":"2.1","title":"木瓜带湿鱼","descriptions":[
                    {"title":"【组成】","content":"<p>&nbsp;木瓜250克&amp;鲜鱼</p>"}]},
                  {"id":"2.6","title":"推荐商品","children":[
                    {"id":"2.6.1","title":"滇绿",
                     "descriptions":[{"title":"【推荐理由】","content":"<p>清热&nbsp;解毒</p>"}]}]}
                ]}]}
                """);

        List<TongueReportGroup> groups = TongueReportParser.groups(data);
        TongueReportSection section = find(groups.get(0).sections(), "2.1");

        assertNotNull(section);
        assertEquals("【组成】", section.descriptions().get(0).title());
        assertEquals("木瓜250克&鲜鱼", section.descriptions().get(0).content(),
                "描述必须去标签、解实体、折叠空白");

        TongueProductItem product = groups.get(0).products().get(0);
        assertEquals("滇绿", product.goodsName());
        assertEquals("【推荐理由】 清热 解毒", product.reason(), "推荐理由必须是纯文本");
        assertFalse(product.reason().contains("<p>"));
        assertFalse(product.reason().contains("&nbsp;"));
    }

    @Test
    @DisplayName("Tab 键按关键字容错，未命中归入 other")
    void matchesTabKeyByKeyword() {
        assertEquals("overview", TongueReportParser.tabKey("体质概况"));
        assertEquals("diet", TongueReportParser.tabKey("饮食调理"));
        assertEquals("diet", TongueReportParser.tabKey("饮食建议"));
        assertEquals("acupoint", TongueReportParser.tabKey("经络穴位"));
        assertEquals("emotion", TongueReportParser.tabKey("情志与心理"));
        assertEquals("sport", TongueReportParser.tabKey("运动健身"));
        assertEquals("other", TongueReportParser.tabKey("其他建议"));
        assertEquals("other", TongueReportParser.tabKey(null));
    }

    private JsonNode fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/tongue/report-fixture.json")) {
            assertNotNull(input, "缺少舌象真实结构 fixture");
            return objectMapper.readTree(input);
        }
    }

    private static List<TongueReportSection> flatten(List<TongueReportGroup> groups) {
        List<TongueReportSection> result = new ArrayList<>();
        for (TongueReportGroup group : groups) {
            result.addAll(group.sections());
        }
        return result;
    }

    private static TongueReportSection find(List<TongueReportSection> sections, String id) {
        return sections.stream().filter(section -> id.equals(section.id())).findFirst().orElse(null);
    }
}
