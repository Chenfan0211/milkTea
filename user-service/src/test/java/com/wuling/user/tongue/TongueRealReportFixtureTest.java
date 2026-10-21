package com.wuling.user.tongue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.user.dto.tongue.TongueProductItem;
import com.wuling.user.dto.tongue.TongueReportGroup;
import com.wuling.user.dto.tongue.TongueReportSection;
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

/**
 * 真实第三方报文（湿热质，2026-10-16 抓取）的清洗与结构回归。
 *
 * <p>fixture 由线上 {@code /api/v1/app/tongue/reports/{id}} 的响应还原成第三方原始树，
 * 刻意保留 {@code <p style>}、{@code <font>}、{@code &nbsp;} 等脏数据形态，
 * 用来锁住「详情页不再显示标签与实体」这条链路。
 */
class TongueRealReportFixtureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("真实报文：五个分组映射到五个固定 Tab")
    void mapsRealReportToTabs() throws Exception {
        List<TongueReportGroup> groups = TongueReportParser.groups(fixture());

        assertEquals(List.of("概况", "饮食", "穴位", "情志", "运动"),
                groups.stream().map(TongueReportGroup::title).toList());
        assertEquals(List.of("overview", "diet", "acupoint", "emotion", "sport"),
                groups.stream().map(TongueReportGroup::tabKey).toList());
    }

    @Test
    @DisplayName("真实报文：饮食分组只有推荐商品，且商品保留名称与推荐理由")
    void keepsDietRecommendationsWithoutMallMatch() throws Exception {
        TongueReportGroup diet = group("饮食");
        List<TongueProductItem> products = diet.products();

        assertTrue(diet.sections().isEmpty(), "该体质的第三方报文里饮食组没有其它章节");
        assertEquals(5, products.size(), "饮食 Tab 的内容来源就是这 5 条推荐商品");
        assertEquals(List.of("滇绿", "葛根枳椇茶", "金银菊花茶", "绿茶", "茉莉花茶"),
                products.stream().map(TongueProductItem::goodsName).toList());

        for (TongueProductItem product : products) {
            assertTrue(product.reason().contains("【推荐理由】"));
            assertFalse(product.reason().contains("<"), "推荐理由必须是纯文本");
            assertFalse(product.reason().contains("&nbsp;"), "推荐理由不得残留实体");
        }

        // 商城没有同名商品时：matched=false 但商品仍在，供小程序渲染文字卡（不得整块消失）
        List<TongueProductItem> unmatched = TongueReportParser.matchProducts(products, Map.of());
        assertEquals(5, unmatched.size());
        assertTrue(unmatched.stream().noneMatch(TongueProductItem::matched));
        assertNull(unmatched.get(0).price());
        assertTrue(unmatched.get(0).suitableConstitutions().isEmpty(), "未命中商城的商品不得伪造适宜体质");
        assertNull(unmatched.get(0).image());
    }

    @Test
    @DisplayName("真实报文：正文保留白名单标签并剔除 style/font")
    void cleansRealReportContent() throws Exception {
        List<TongueReportSection> sections = sections();

        TongueReportSection overview = find(sections, "1.1");
        assertNotNull(overview);
        assertTrue(overview.content().contains("<p>"), "段落标签保留，供 rich-text 渲染");
        assertFalse(overview.content().contains("style="), "style 属性必须剔除");
        assertFalse(overview.content().contains("<font"), "font 标签必须剔除");
        assertTrue(overview.content().contains("湿热质"));

        TongueReportSection regulation = find(sections, "1.7");
        assertNotNull(regulation);
        assertFalse(regulation.content().contains("&nbsp;"), "实体必须解码");
        assertTrue(regulation.content().contains("<p>4、"), "多段正文按段落保留");
    }

    @Test
    @DisplayName("真实报文：描述全部转纯文本")
    void cleansRealReportDescriptions() throws Exception {
        List<TongueReportSection> sections = sections();

        TongueReportSection health = find(sections, "1.5");
        assertNotNull(health);
        assertEquals(2, health.descriptions().size());
        for (var item : health.descriptions()) {
            String title = item.title() == null ? "" : item.title();
            String content = item.content() == null ? "" : item.content();
            assertFalse(title.contains("<"));
            assertFalse(content.contains("<"), "描述内容不得残留标签: " + content);
            assertFalse(content.contains("&nbsp;"), "描述内容不得残留实体");
        }
        assertTrue(health.descriptions().get(1).content().contains("面垢油光"));

        TongueReportSection music = find(sections, "4.2");
        assertNotNull(music);
        String fiveTones = music.descriptions().get(0).content();
        assertFalse(fiveTones.contains("<b>"), "加粗标签在纯文本字段里必须去掉");
        assertTrue(fiveTones.contains("宫音"));
    }

    @Test
    @DisplayName("真实报文：层级、渲染块与媒体类型正确")
    void mapsRealReportHierarchyAndBlocks() throws Exception {
        List<TongueReportSection> sections = sections();

        TongueReportSection analysis = find(sections, "1.4");
        assertNotNull(analysis);
        assertEquals(TongueReportSection.BLOCK_ANALYSIS, analysis.block());
        assertEquals("1", analysis.parentId());
        assertEquals(5, analysis.analysis().factors().size());
        assertEquals(5, analysis.analysis().features().size());

        TongueReportSection acupointGroup = find(sections, "3.2");
        assertNotNull(acupointGroup);
        assertEquals(TongueReportSection.BLOCK_TITLE_ONLY, acupointGroup.block());

        TongueReportSection acupoint = find(sections, "3.2.1");
        assertNotNull(acupoint);
        assertEquals("3.2", acupoint.parentId());
        assertEquals(TongueReportSection.BLOCK_MEDIA_DETAIL, acupoint.block());
        assertEquals("VIDEO", acupoint.media().kind());
        assertEquals(3, acupoint.descriptions().size());

        TongueReportSection audio = find(sections, "4.2.1");
        assertNotNull(audio);
        assertEquals("4.2", audio.parentId());
        assertEquals(TongueReportSection.BLOCK_MEDIA_AUDIO, audio.block(), "第三方把 mp3 标成 video，必须按后缀纠正为音频");

        TongueReportSection meditation = find(sections, "4.3.1");
        assertNotNull(meditation);
        assertEquals(TongueReportSection.BLOCK_MEDIA_VIDEO, meditation.block());

        for (String id : List.of("5.2.1", "5.2.2", "5.2.3")) {
            TongueReportSection fitness = find(sections, id);
            assertNotNull(fitness, "缺少健身功法章节 " + id);
            assertEquals("5.2", fitness.parentId());
            assertEquals(TongueReportSection.BLOCK_MEDIA_DETAIL, fitness.block());
            assertEquals(1, fitness.descriptions().size());
        }
    }

    private TongueReportGroup group(String title) throws Exception {
        return TongueReportParser.groups(fixture()).stream()
                .filter(item -> title.equals(item.title()))
                .findFirst()
                .orElseThrow();
    }

    private List<TongueReportSection> sections() throws Exception {
        List<TongueReportSection> result = new ArrayList<>();
        for (TongueReportGroup group : TongueReportParser.groups(fixture())) {
            result.addAll(group.sections());
        }
        return result;
    }

    private JsonNode fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/tongue/report-real-heat.json")) {
            assertNotNull(input, "缺少真实报文 fixture");
            return objectMapper.readTree(input);
        }
    }

    private static TongueReportSection find(List<TongueReportSection> sections, String id) {
        return sections.stream().filter(section -> id.equals(section.id())).findFirst().orElse(null);
    }
}