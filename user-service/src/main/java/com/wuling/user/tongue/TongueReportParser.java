package com.wuling.user.tongue;

import com.fasterxml.jackson.databind.JsonNode;
import com.wuling.user.dto.tongue.TongueAnalysis;
import com.wuling.user.dto.tongue.TongueProductItem;
import com.wuling.user.dto.tongue.TongueReportDescription;
import com.wuling.user.dto.tongue.TongueReportGroup;
import com.wuling.user.dto.tongue.TongueReportMedia;
import com.wuling.user.dto.tongue.TongueReportSection;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 舌象第三方报告解析器。
 *
 * <p>原始 data JSON 原样保存；结构化字段只做只读提取，不修改第三方节点。
 * 解析结果直接决定小程序「详情」页五个 Tab 的渲染器选择，因此每个章节都会
 * 附带 tabKey / parentId / block 三个视图字段。
 */
public final class TongueReportParser {

    private TongueReportParser() {
    }

    /**
     * 第三方一级标题关键字 → 小程序详情页 Tab 键。
     *
     * <p>按关键字「包含」匹配而不是等值匹配：第三方标题存在「饮食调理」「经络穴位」
     * 这类写法，等值匹配会把整组挤成额外 Tab、让固定 Tab 空掉。
     */
    private static final String[][] TAB_KEYWORDS = {
            {"概况", "overview"},
            {"概览", "overview"},
            {"饮食", "diet"},
            {"穴位", "acupoint"},
            {"经络", "acupoint"},
            {"情志", "emotion"},
            {"心理", "emotion"},
            {"运动", "sport"},
            {"健身", "sport"}
    };

    /** script / style 整段（含内容）剔除。 */
    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    /** 允许保留的正文标签（属性会在清洗时被去掉）。 */
    private static final Pattern ALLOWED_TAG = Pattern.compile(
            "(?i)</?(?:p|br|strong|em|u|ul|ol|li|span)\\b[^>]*/?>");
    /** 任意标签，用于把非白名单标签整段丢弃。 */
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]*>");

    /**
     * 按一级节点分组，并保留组内层级。
     */
    public static List<TongueReportGroup> groups(JsonNode data) {
        JsonNode roots = findNode(data, "children", "sections");
        if (roots == null || !roots.isArray()) {
            return List.of();
        }
        List<TongueReportGroup> result = new ArrayList<>();
        for (JsonNode root : roots) {
            String title = text(root, "title", "name");
            if (!StringUtils.hasText(title)) {
                continue;
            }
            List<TongueProductItem> products = productsFromReport(root);
            List<TongueReportSection> sections = new ArrayList<>();
            flattenSections(findNode(root, "children", "sections"), 1, text(root, "id"), sections);
            result.add(new TongueReportGroup(text(root, "id"), title.trim(), tabKey(title), products,
                    List.copyOf(sections)));
        }
        return List.copyOf(result);
    }

    /** 一级标题 → Tab 键；未命中返回 other（小程序追加为额外 Tab，内容不丢）。 */
    public static String tabKey(String title) {
        if (!StringUtils.hasText(title)) {
            return "other";
        }
        String value = title.trim();
        for (String[] entry : TAB_KEYWORDS) {
            if (value.contains(entry[0])) {
                return entry[1];
            }
        }
        return "other";
    }

    /**
     * 解析第三方顶层商品列表，保留兼容调用。
     */
    public static List<TongueProductItem> products(JsonNode data) {
        JsonNode list = findNode(data, "productList", "product_list", "products");
        if (list == null && data != null && data.isArray()) {
            list = data;
        }
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<TongueProductItem> result = new ArrayList<>();
        for (JsonNode item : list) {
            TongueProductItem product = product(item, null);
            if (product != null) {
                result.add(product);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 将检测阶段商品与报告中的商品理由合并，避免刷新报告时商品被清空。
     */
    public static List<TongueProductItem> mergeProducts(List<TongueProductItem> existing, JsonNode data) {
        Map<String, TongueProductItem> merged = new LinkedHashMap<>();
        if (existing != null) {
            for (TongueProductItem item : existing) {
                if (item != null) {
                    merged.put(productKey(item.goodsId(), item.goodsName()), item);
                }
            }
        }
        for (TongueProductItem item : products(data)) {
            mergeOne(merged, item);
        }
        for (TongueProductItem item : reportProducts(data)) {
            mergeOne(merged, item);
        }
        return List.copyOf(merged.values());
    }

    /**
     * 把商城商品卡挂到推荐商品上：按归一化商品名匹配，命中即 matched=true。
     *
     * <p>未命中的商品保留原始信息（matched=false），由小程序决定不渲染。
     */
    public static List<TongueProductItem> matchProducts(List<TongueProductItem> items,
                                                        Map<String, ProductCard> cardsByNormalizedName) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, ProductCard> cards = cardsByNormalizedName == null ? Map.of() : cardsByNormalizedName;
        List<TongueProductItem> result = new ArrayList<>(items.size());
        for (TongueProductItem item : items) {
            if (item == null) {
                continue;
            }
            ProductCard card = cards.get(normalizeName(item.goodsName()));
            if (card == null) {
                result.add(new TongueProductItem(item.goodsId(), item.goodsName(), item.reason(), item.media(),
                        null, null, null, null, List.of(), List.of(), false));
                continue;
            }
            result.add(new TongueProductItem(item.goodsId(), item.goodsName(), item.reason(), item.media(),
                    card.productId(), card.image(), card.price(), card.originalPrice(),
                    card.tags() == null ? List.of() : card.tags(),
                    card.suitableConstitutions() == null ? List.of() : card.suitableConstitutions(),
                    true));
        }
        return List.copyOf(result);
    }

    /** 按同一匹配规则处理每个分组内的推荐商品。 */
    public static List<TongueReportGroup> matchGroupProducts(List<TongueReportGroup> groups,
                                                            Map<String, ProductCard> cardsByNormalizedName) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }
        List<TongueReportGroup> result = new ArrayList<>(groups.size());
        for (TongueReportGroup group : groups) {
            if (group == null) {
                continue;
            }
            result.add(new TongueReportGroup(group.id(), group.title(), group.tabKey(),
                    matchProducts(group.products(), cardsByNormalizedName), group.sections()));
        }
        return List.copyOf(result);
    }

    /** 收集需要查商城的商品名（去重、去空）。 */
    public static List<String> productNames(List<TongueProductItem> items) {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (TongueProductItem item : items == null ? List.<TongueProductItem>of() : items) {
            if (item != null && StringUtils.hasText(item.goodsName())) {
                names.putIfAbsent(normalizeName(item.goodsName()), item.goodsName().trim());
            }
        }
        return List.copyOf(names.values());
    }

    private static void mergeOne(Map<String, TongueProductItem> merged, TongueProductItem incoming) {
        if (incoming == null || !StringUtils.hasText(incoming.goodsName())) {
            return;
        }
        TongueProductItem current = merged.get(productKey(incoming.goodsId(), incoming.goodsName()));
        if (current == null) {
            for (TongueProductItem candidate : merged.values()) {
                if (sameName(candidate.goodsName(), incoming.goodsName())) {
                    current = candidate;
                    break;
                }
            }
        }
        String goodsId = current != null && StringUtils.hasText(current.goodsId())
                ? current.goodsId() : incoming.goodsId();
        String reason = StringUtils.hasText(incoming.reason())
                ? incoming.reason() : current == null ? null : current.reason();
        TongueReportMedia media = incoming.media() != null
                ? incoming.media() : current == null ? null : current.media();
        TongueProductItem value = new TongueProductItem(goodsId, incoming.goodsName(), reason, media,
                null, null, null, null, List.of(), List.of(), false);
        merged.put(productKey(goodsId, incoming.goodsName()), value);
    }

    private static List<TongueProductItem> reportProducts(JsonNode data) {
        JsonNode roots = findNode(data, "children", "sections");
        if (roots == null || !roots.isArray()) {
            return List.of();
        }
        List<TongueProductItem> result = new ArrayList<>();
        for (JsonNode root : roots) {
            result.addAll(productsFromReport(root));
        }
        return List.copyOf(result);
    }

    private static List<TongueProductItem> productsFromReport(JsonNode root) {
        JsonNode recommendation = findByTitle(root, "推荐商品");
        if (recommendation == null) {
            return List.of();
        }
        List<TongueProductItem> result = new ArrayList<>();
        JsonNode children = findNode(recommendation, "children", "sections");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                TongueProductItem product = product(child, reasonOf(child));
                if (product != null) {
                    result.add(product);
                }
            }
        }
        return List.copyOf(result);
    }

    private static TongueProductItem product(JsonNode node, String reason) {
        String goodsId = text(node, "goodsId", "goods_id", "productId", "product_id", "id");
        String goodsName = text(node, "goodsName", "goods_name", "productName", "product_name", "name", "title");
        if (!StringUtils.hasText(goodsId) && !StringUtils.hasText(goodsName)) {
            return null;
        }
        return new TongueProductItem(goodsId, goodsName, reason, media(node),
                null, null, null, null, List.of(), List.of(), false);
    }

    private static String reasonOf(JsonNode node) {
        List<TongueReportDescription> descriptions = descriptions(node);
        if (descriptions.isEmpty()) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (TongueReportDescription item : descriptions) {
            if (result.length() > 0) {
                result.append('\n');
            }
            if (StringUtils.hasText(item.title())) {
                result.append(item.title()).append(' ');
            }
            if (StringUtils.hasText(item.content())) {
                result.append(item.content().trim());
            }
        }
        return result.length() == 0 ? null : result.toString();
    }

    private static void flattenSections(JsonNode nodes, int level, String parentId,
                                        List<TongueReportSection> result) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            String title = text(node, "title", "name");
            if (!StringUtils.hasText(title) || "推荐商品".equals(title.trim())) {
                continue;
            }
            JsonNode childNodes = findNode(node, "children", "sections");
            List<TongueReportDescription> descriptions = descriptions(node);
            TongueReportMedia media = media(node);
            TongueAnalysis analysis = analysis(findNode(node, "props"));
            String content = content(node);
            boolean hasChildren = childNodes != null && childNodes.isArray() && !childNodes.isEmpty();
            String id = text(node, "id");
            if (StringUtils.hasText(content) || !descriptions.isEmpty() || media != null
                    || analysis != null || hasChildren) {
                result.add(new TongueReportSection(id, level, parentId, title.trim(),
                        block(analysis, media, descriptions, content, hasChildren),
                        content, descriptions, media, analysis));
            }
            flattenSections(childNodes, level + 1, id, result);
        }
    }

    /**
     * 章节内容形态：决定小程序的渲染器。
     *
     * <p>优先级：分析指标 &gt; 媒体+描述 &gt; 媒体 &gt; 描述 &gt; 正文 &gt; 纯父节点。
     */
    private static String block(TongueAnalysis analysis, TongueReportMedia media,
                                List<TongueReportDescription> descriptions, String content,
                                boolean hasChildren) {
        if (analysis != null) {
            return TongueReportSection.BLOCK_ANALYSIS;
        }
        if (media != null && !descriptions.isEmpty()) {
            return TongueReportSection.BLOCK_MEDIA_DETAIL;
        }
        if (media != null) {
            return switch (media.kind() == null ? "" : media.kind().toUpperCase(Locale.ROOT)) {
                case "AUDIO" -> TongueReportSection.BLOCK_MEDIA_AUDIO;
                case "VIDEO" -> TongueReportSection.BLOCK_MEDIA_VIDEO;
                default -> TongueReportSection.BLOCK_MEDIA_IMAGE;
            };
        }
        if (!descriptions.isEmpty()) {
            return TongueReportSection.BLOCK_DESCRIPTIONS;
        }
        if (StringUtils.hasText(content)) {
            return TongueReportSection.BLOCK_TEXT;
        }
        return TongueReportSection.BLOCK_TITLE_ONLY;
    }

    private static List<TongueReportDescription> descriptions(JsonNode node) {
        JsonNode values = findNode(node, "descriptions");
        if (values == null || !values.isArray()) {
            return List.of();
        }
        List<TongueReportDescription> result = new ArrayList<>();
        for (JsonNode item : values) {
            // 第三方描述里常混 <p>&nbsp; 等标签/实体，统一转纯文本后再下发
            String title = plainText(text(item, "title"));
            String content = plainText(text(item, "content", "value"));
            if (StringUtils.hasText(title) || StringUtils.hasText(content)) {
                result.add(new TongueReportDescription(title, content));
            }
        }
        return List.copyOf(result);
    }

    private static TongueReportMedia media(JsonNode node) {
        JsonNode media = findNode(node, "media");
        if (media == null || !media.isObject()) {
            return null;
        }
        String type = text(media, "type");
        String thumbnail = text(media, "thumbnail");
        String source = text(media, "source");
        String kind = mediaKind(type, source, thumbnail);
        if (!StringUtils.hasText(kind)) {
            return null;
        }
        if (!StringUtils.hasText(thumbnail) && !StringUtils.hasText(source)
                && !"picture".equalsIgnoreCase(type)) {
            return null;
        }
        String duration = findText(media, "duration", "time", "length");
        return new TongueReportMedia(kind, thumbnail, source, StringUtils.hasText(duration) ? duration : null);
    }

    private static String mediaKind(String declaredType, String source, String thumbnail) {
        String value = StringUtils.hasText(source) ? source : thumbnail;
        String path = pathOf(value).toLowerCase(Locale.ROOT);
        if (path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".wav") || path.endsWith(".aac")) {
            return "AUDIO";
        }
        if (path.endsWith(".mp4") || path.endsWith(".mov") || path.endsWith(".m3u8")) {
            return "VIDEO";
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
                || path.endsWith(".webp") || path.endsWith(".gif")) {
            return "IMAGE";
        }
        if ("picture".equalsIgnoreCase(declaredType)) {
            return "IMAGE";
        }
        if ("video".equalsIgnoreCase(declaredType)) {
            return StringUtils.hasText(source) ? "VIDEO" : null;
        }
        return null;
    }

    private static TongueAnalysis analysis(JsonNode props) {
        if (props == null || !props.isObject()) {
            return null;
        }
        List<TongueAnalysis.Factor> factors = new ArrayList<>();
        JsonNode factorNodes = props.get("factor");
        if (factorNodes != null && factorNodes.isArray()) {
            for (JsonNode item : factorNodes) {
                factors.add(new TongueAnalysis.Factor(text(item, "tongueType"), text(item, "tongueVal"), text(item, "highlight")));
            }
        }
        List<TongueAnalysis.Feature> features = new ArrayList<>();
        JsonNode featureNodes = props.get("feature");
        if (featureNodes != null && featureNodes.isArray()) {
            for (JsonNode item : featureNodes) {
                features.add(new TongueAnalysis.Feature(text(item, "tongueType"), text(item, "tongueVal"), text(item, "difference")));
            }
        }
        if (factors.isEmpty() && features.isEmpty()) {
            return null;
        }
        return new TongueAnalysis(List.copyOf(factors), List.copyOf(features));
    }

    public static String summarize(JsonNode data) {
        String raw = findText(data, "summarize", "summary");
        if (StringUtils.hasText(raw)) {
            return truncate(stripHtml(raw), 200);
        }
        JsonNode node = findById(data, "1.1");
        if (node == null) {
            node = findByTitle(data, "体质概述");
        }
        String content = node == null ? null : content(node);
        if (StringUtils.hasText(content)) {
            return truncate(stripHtml(content), 200);
        }
        node = findById(data, "1.5");
        if (node != null) {
            List<TongueReportDescription> items = descriptions(node);
            if (!items.isEmpty()) {
                return truncate(stripHtml(items.get(0).content()), 200);
            }
        }
        return null;
    }

    public static String reportJson(JsonNode data) {
        return data == null || data.isNull() ? null : data.toString();
    }

    public static String reportStatus(JsonNode data, String fallback) {
        String raw = findText(data, "reportStatus", "report_status");
        if (!StringUtils.hasText(raw)) {
            JsonNode status = findNode(data, "status");
            raw = status != null && status.isTextual() ? status.asText() : null;
        }
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "READY", "DONE", "SUCCESS", "SUCCEED", "FINISHED", "COMPLETED", "1", "200" -> "READY";
            case "PENDING", "PROCESSING", "WAIT", "WAITING", "RUNNING", "0" -> "PENDING";
            case "FAILED", "FAIL", "ERROR", "2", "-1" -> "FAILED";
            default -> fallback;
        };
    }

    public static boolean hasReportContent(JsonNode data) {
        return !groups(data).isEmpty();
    }

    public static String findText(JsonNode node, String... names) {
        JsonNode value = findNode(node, names);
        if (value == null) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    public static JsonNode findNode(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static JsonNode findById(JsonNode node, String id) {
        if (node == null) {
            return null;
        }
        if (node.isObject() && id.equals(text(node, "id"))) {
            return node;
        }
        JsonNode children = findNode(node, "children", "sections");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                JsonNode found = findById(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JsonNode findByTitle(JsonNode node, String title) {
        if (node == null) {
            return null;
        }
        if (node.isObject() && title.equals(text(node, "title", "name"))) {
            return node;
        }
        JsonNode children = findNode(node, "children", "sections");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                JsonNode found = findByTitle(child, title);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 正文：走白名单 HTML，供小程序 rich-text 渲染。 */
    private static String content(JsonNode node) {
        return sanitizeHtml(text(node, "content", "value"));
    }

    private static String text(JsonNode node, String... names) {
        JsonNode value = findNode(node, names);
        if (value == null) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static String pathOf(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        try {
            String path = URI.create(value).getPath();
            return StringUtils.hasText(path) ? path : value;
        } catch (Exception e) {
            return value;
        }
    }

    /** 摘要等场景沿用：等价于纯文本化。 */
    private static String stripHtml(String value) {
        return plainText(value);
    }

    /**
     * 正文白名单清洗。
     *
     * <p>规则：剔除 script/style 整段；只保留 p/br/strong/em/u/ul/ol/li/span
     * 且去掉全部属性；文本段解实体后重新转义 < > &，避免第三方的
     * {@code <p>&nbsp;…</p>} 在小程序里变成可见标签或 &amp;nbsp; 文字。
     */
    private static String sanitizeHtml(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String html = SCRIPT_OR_STYLE.matcher(value).replaceAll("");
        StringBuilder out = new StringBuilder();
        Matcher matcher = ALLOWED_TAG.matcher(html);
        int last = 0;
        while (matcher.find()) {
            out.append(escapeText(html.substring(last, matcher.start())));
            out.append(normalizeAllowedTag(matcher.group()));
            last = matcher.end();
        }
        out.append(escapeText(html.substring(last)));
        String result = out.toString()
                .replaceAll("(?i)<p>\\s*</p>", "")
                .replaceAll("\\s+", " ")
                .trim();
        return StringUtils.hasText(result) ? result : null;
    }

    /** 纯文本化：去标签、解实体、折叠空白；用于描述、推荐理由与摘要。 */
    private static String plainText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = SCRIPT_OR_STYLE.matcher(value).replaceAll(" ");
        text = text.replaceAll("(?i)<br\\s*/?>", " ");
        text = text.replaceAll("(?i)</?(?:p|div|li|ul|ol|tr|td|table)[^>]*>", " ");
        text = ANY_TAG.matcher(text).replaceAll(" ");
        text = decodeEntities(text).replace('\u00a0', ' ');
        text = text.replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? null : text;
    }

    /** 文本段：先丢残留标签，再解实体，最后转义，保证 rich-text 里不会出现裸标签。 */
    private static String escapeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String text = ANY_TAG.matcher(value).replaceAll(" ");
        text = decodeEntities(text);
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 保留标签统一成无属性的标准写法；br 统一成自闭合。 */
    private static String normalizeAllowedTag(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        boolean closing = lower.startsWith("</");
        String body = lower.replace("<", "").replace(">", "").replace("/", "").trim();
        int space = body.indexOf(' ');
        String name = space >= 0 ? body.substring(0, space) : body;
        if (name.isEmpty()) {
            return "";
        }
        if ("br".equals(name)) {
            return "<br/>";
        }
        return closing ? "</" + name + ">" : "<" + name + ">";
    }

    /** HTML 实体解码；&amp; 放最后，避免二次解码。 */
    private static String decodeEntities(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }

    private static String productKey(String goodsId, String goodsName) {
        return StringUtils.hasText(goodsId) ? "id:" + goodsId.trim() : "name:" + normalizeName(goodsName);
    }

    private static boolean sameName(String left, String right) {
        return StringUtils.hasText(left) && StringUtils.hasText(right)
                && normalizeName(left).equals(normalizeName(right));
    }

    /** 商品名归一化：去空白、转小写，用于「报告商品名 → 商城商品」匹配。 */
    public static String normalizeName(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }
}