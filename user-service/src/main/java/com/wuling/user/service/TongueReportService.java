package com.wuling.user.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import com.wuling.user.dto.tongue.TongueDetectionResponse;
import com.wuling.user.dto.tongue.TongueProductItem;
import com.wuling.user.dto.tongue.TongueReportGroup;
import com.wuling.user.dto.tongue.TongueReportDetailResponse;
import com.wuling.user.dto.tongue.TongueReportSummaryResponse;
import com.wuling.user.entity.TongueReport;
import com.wuling.user.mapper.TongueReportMapper;
import com.wuling.user.tongue.TongueApiResponse;
import com.wuling.user.tongue.TongueClientException;
import com.wuling.user.tongue.TongueDetectionClient;
import com.wuling.user.tongue.TongueFileClient;
import com.wuling.user.tongue.ProductCard;
import com.wuling.user.tongue.ProductCardClient;
import com.wuling.user.tongue.TongueReportParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 舌象检测与报告服务。
 *
 * <p>所有查询都以 JWT 中的 userId 作为归属条件；未命中统一返回不存在，
 * 避免通过 ID 探测他人记录。
 */
@Service
public class TongueReportService {

    private static final Logger log = LoggerFactory.getLogger(TongueReportService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int RECENT_MONTHS = 6;
    private static final Duration REFRESH_LIMIT = Duration.ofSeconds(10);
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_FAILED = "FAILED";

    private static final List<String> PENDING_MESSAGES = List.of(
            "数据不存在", "报告不存在", "暂无数据", "暂无报告", "未生成", "生成中", "未完成", "处理中", "排队中", "等待中",
            "not exist", "not found", "pending", "processing", "waiting", "wait"
    );

    private final TongueReportMapper mapper;
    private final TongueFileClient fileClient;
    private final TongueDetectionClient detectionClient;
    private final RedisManager redisManager;
    private final ProductCardClient productCardClient;

    public TongueReportService(TongueReportMapper mapper,
                               TongueFileClient fileClient,
                               TongueDetectionClient detectionClient,
                               RedisManager redisManager,
                               ProductCardClient productCardClient) {
        this.mapper = mapper;
        this.fileClient = fileClient;
        this.detectionClient = detectionClient;
        this.redisManager = redisManager;
        this.productCardClient = productCardClient;
    }

    /**
     * 上传图片 -> 调第三方检测 -> 成功落库；任一步失败补偿删除已上传图片。
     */
    public TongueDetectionResponse detect(Long userId, MultipartFile image, Integer age, String sex) {
        Long uid = requireUserId(userId);
        String normalizedSex = normalizeSex(sex);
        int normalizedAge = normalizeAge(age);
        byte[] imageBytes = readImage(image);

        TongueFileClient.StoredFile stored;
        try {
            stored = fileClient.upload(image);
        } catch (Exception e) {
            throw asBusiness(e, "图片保存失败，请稍后重试");
        }
        if (stored == null || !StringUtils.hasText(stored.storedName())) {
            throw new BusinessException(ResultCode.ERROR, "图片保存失败，请稍后重试");
        }

        try {
            String mimeType = resolveMimeType(stored.mimeType(), image.getContentType(), imageBytes);
            String imageDataUrl = "data:" + mimeType + ";base64,"
                    + Base64.getEncoder().encodeToString(imageBytes);
            TongueApiResponse response = detectionClient.detect(
                    toThirdPartySex(normalizedSex), normalizedAge, imageDataUrl);
            if (response == null || !response.success()) {
                throw new BusinessException(ResultCode.ERROR,
                        failureMessage(response, "舌象检测失败，请稍后重试"));
            }

            JsonNode data = response.data();
            String reportId = TongueReportParser.findText(data, "reportId", "report_id");
            if (!StringUtils.hasText(reportId)) {
                throw new BusinessException(ResultCode.ERROR, "舌象检测未返回报告编号");
            }

            TongueReport existing = mapper.selectByReportId(reportId.trim());
            if (existing != null) {
                compensateDelete(stored.storedName());
                return reuseExistingReport(existing, uid);
            }

            LocalDateTime now = LocalDateTime.now();
            TongueReport report = new TongueReport();
            report.setUserId(uid);
            report.setReportId(reportId.trim());
            report.setSex(normalizedSex);
            report.setAge(normalizedAge);
            report.setImageStorageName(stored.storedName());
            report.setReportStatus(resolveDetectionStatus(data));
            report.setLastFetchAt(now);
            report.setCreateTime(now);
            report.setUpdateTime(now);
            fillReportContent(report, data);
            int inserted;
            try {
                inserted = mapper.insert(report);
            } catch (DuplicateKeyException e) {
                TongueReport concurrent = mapper.selectByReportId(reportId.trim());
                if (concurrent != null) {
                    compensateDelete(stored.storedName());
                    return reuseExistingReport(concurrent, uid);
                }
                throw e;
            }
            if (inserted <= 0) {
                throw new BusinessException(ResultCode.ERROR, "舌象报告保存失败，请稍后重试");
            }
            return toDetectionResponse(report);
        } catch (Exception e) {
            compensateDelete(stored.storedName());
            throw asBusiness(e, "舌象检测失败，请稍后重试");
        }
    }

    public List<TongueReportSummaryResponse> listRecent(Long userId) {
        Long uid = requireUserId(userId);
        LocalDateTime since = LocalDateTime.now().minusMonths(RECENT_MONTHS);
        List<TongueReport> reports = mapper.selectRecentByUser(uid, since);
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        return reports.stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    public TongueReportDetailResponse detail(Long userId, Long id) {
        return toDetailResponse(requireOwnedRecent(requireUserId(userId), id));
    }

    /**
     * READY 直接返回；非 READY 每用户 10 秒最多触发一次第三方查询。
     */
    public TongueReportDetailResponse refresh(Long userId, Long id) {
        Long uid = requireUserId(userId);
        TongueReport report = requireOwnedRecent(uid, id);
        if (STATUS_READY.equals(report.getReportStatus())) {
            return toDetailResponse(report);
        }
        if (!acquireRefreshPermit(uid)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "操作过于频繁，请10秒后再试");
        }

        LocalDateTime fetchAt = LocalDateTime.now();
        TongueApiResponse response;
        try {
            response = detectionClient.fetchReport(report.getReportId());
        } catch (Exception e) {
            markFailed(report, fetchAt);
            throw asBusiness(e, "舌象报告获取失败，请稍后重试");
        }

        if (response != null && response.success()) {
            applyReportData(report, response.data(), fetchAt);
            mapper.updateById(report);
            if (STATUS_FAILED.equals(report.getReportStatus())) {
                throw new BusinessException(ResultCode.ERROR, "舌象报告获取失败，请稍后重试");
            }
            return toDetailResponse(report);
        }

        if (response != null && isPendingMessage(response.message())) {
            report.setReportStatus(STATUS_PENDING);
            report.setLastFetchAt(fetchAt);
            report.setUpdateTime(fetchAt);
            mapper.updateById(report);
            return toDetailResponse(report);
        }

        markFailed(report, fetchAt);
        throw new BusinessException(ResultCode.ERROR,
                "舌象报告获取失败：" + safeMessage(response == null ? null : response.message(), "请稍后重试"));
    }

    public ImageContent image(Long userId, Long id) {
        TongueReport report = requireOwnedRecent(requireUserId(userId), id);
        try {
            TongueFileClient.ImageBytes image = fileClient.download(report.getImageStorageName());
            return new ImageContent(image.bytes(), image.mimeType());
        } catch (Exception e) {
            String message = e instanceof TongueClientException ? e.getMessage() : null;
            if (StringUtils.hasText(message) && message.contains("不存在")) {
                throw new BusinessException(ResultCode.NOT_FOUND, "图片不存在或已删除");
            }
            throw new BusinessException(ResultCode.ERROR, "图片读取失败，请稍后重试");
        }
    }

    /**
     * 逻辑删除记录，不删除 file-service 图片，也不物理删除行。
     */
    public void delete(Long userId, Long id) {
        TongueReport report = requireOwnedRecent(requireUserId(userId), id);
        mapper.deleteById(report.getId());
    }

    private TongueReport requireOwnedRecent(Long userId, Long id) {
        if (id == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "舌象报告不存在或已过期");
        }
        TongueReport report = mapper.selectOwnedRecent(id, userId,
                LocalDateTime.now().minusMonths(RECENT_MONTHS));
        if (report == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "舌象报告不存在或已过期");
        }
        return report;
    }

    private boolean acquireRefreshPermit(Long userId) {
        String key = RedisNamespace.BIZ.key("tongue:refresh:" + userId);
        Boolean acquired = redisManager.template(RedisNamespace.BIZ)
                .opsForValue()
                .setIfAbsent(key, "1", REFRESH_LIMIT);
        return Boolean.TRUE.equals(acquired);
    }

    private void applyReportData(TongueReport report, JsonNode data, LocalDateTime fetchAt) {
        report.setLastFetchAt(fetchAt);
        report.setUpdateTime(fetchAt);
        if (data == null || data.isNull()) {
            report.setReportStatus(STATUS_PENDING);
            return;
        }
        report.setReportStatus(TongueReportParser.reportStatus(data,
                TongueReportParser.hasReportContent(data) ? STATUS_READY : STATUS_PENDING));
        fillReportContent(report, data);
    }

    private void fillReportContent(TongueReport report, JsonNode data) {
        if (data == null || data.isNull()) {
            return;
        }
        report.setPhysique(TongueReportParser.findText(data, "physique"));
        report.setSummarize(TongueReportParser.summarize(data));
        List<TongueProductItem> existingProducts = parseProducts(report.getProductListJson());
        report.setProductListJson(writeJson(TongueReportParser.mergeProducts(existingProducts, data)));
        report.setReportJson(TongueReportParser.reportJson(data));
    }

    private String resolveDetectionStatus(JsonNode data) {
        if (data == null || data.isNull() || !TongueReportParser.hasReportContent(data)) {
            // detection 只返回摘要和 reportId 时，详细报告仍要等 getReport。
            return STATUS_PENDING;
        }
        return TongueReportParser.reportStatus(data, STATUS_READY);
    }

    private void markFailed(TongueReport report, LocalDateTime fetchAt) {
        report.setReportStatus(STATUS_FAILED);
        report.setLastFetchAt(fetchAt);
        report.setUpdateTime(fetchAt);
        mapper.updateById(report);
    }

    private void compensateDelete(String storedName) {
        if (!StringUtils.hasText(storedName)) {
            return;
        }
        try {
            fileClient.delete(storedName);
        } catch (Exception e) {
            log.warn("舌象图片补偿删除失败 type={}", e.getClass().getSimpleName());
        }
    }

    private TongueDetectionResponse reuseExistingReport(TongueReport report, Long userId) {
        if (report == null || !userId.equals(report.getUserId())
                || (report.getDeleted() != null && report.getDeleted() != 0)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "检测报告编号冲突，请重新检测");
        }
        return toDetectionResponse(report);
    }

    private TongueDetectionResponse toDetectionResponse(TongueReport report) {
        EnrichedReport enriched = enrich(report);
        return new TongueDetectionResponse(
                report.getId(),
                report.getPhysique(),
                report.getSummarize(),
                report.getReportStatus(),
                report.getCreateTime(),
                enriched.products(),
                enriched.groups(),
                StringUtils.hasText(report.getImageStorageName())
        );
    }

    private TongueReportSummaryResponse toSummaryResponse(TongueReport report) {
        return new TongueReportSummaryResponse(
                report.getId(),
                report.getPhysique(),
                report.getSummarize(),
                report.getReportStatus(),
                report.getCreateTime(),
                StringUtils.hasText(report.getImageStorageName()),
                report.getSex(),
                report.getAge()
        );
    }

    private TongueReportDetailResponse toDetailResponse(TongueReport report) {
        EnrichedReport enriched = enrich(report);
        return new TongueReportDetailResponse(
                report.getId(),
                report.getPhysique(),
                report.getSummarize(),
                report.getReportStatus(),
                report.getCreateTime(),
                enriched.products(),
                enriched.groups(),
                StringUtils.hasText(report.getImageStorageName()),
                report.getSex(),
                report.getAge()
        );
    }

    /**
     * 读取报告时按「商品名 → 商城商品」补全商品卡。
     *
     * <p>不改库、不回填历史 JSON：匹配结果只作用于本次响应，
     * 商品改价/下架/改名在下次请求即时生效。
     * 商品服务异常时 ProductCardClient 返回空 Map，全部商品按未匹配处理，
     * 由小程序按方案「匹配不到就不渲染」。
     */
    private EnrichedReport enrich(TongueReport report) {
        List<TongueProductItem> products = parseProducts(report.getProductListJson());
        List<TongueReportGroup> groups = parseGroups(report.getReportJson());
        List<String> names = new ArrayList<>(TongueReportParser.productNames(products));
        for (TongueReportGroup group : groups) {
            for (String name : TongueReportParser.productNames(group.products())) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        Map<String, ProductCard> cards = productCardClient.cardsByNames(names);
        return new EnrichedReport(
                TongueReportParser.matchProducts(products, cards),
                TongueReportParser.matchGroupProducts(groups, cards)
        );
    }

    private record EnrichedReport(List<TongueProductItem> products, List<TongueReportGroup> groups) {
    }


    private static List<TongueProductItem> parseProducts(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return TongueReportParser.products(JSON.readTree(json));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<TongueReportGroup> parseGroups(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return TongueReportParser.groups(JSON.readTree(json));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("报告数据序列化失败", e);
        }
    }

    private static Long requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return userId;
    }

    private static String normalizeSex(String sex) {
        String value = sex == null ? "" : sex.trim().toLowerCase(Locale.ROOT);
        if ("male".equals(value) || "female".equals(value)) {
            return value;
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "性别取值不合法");
    }

    private static int normalizeAge(Integer age) {
        if (age == null || age < 0 || age > 150) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "年龄取值不合法");
        }
        return age;
    }

    private static String toThirdPartySex(String normalizedSex) {
        return "male".equals(normalizedSex) ? "1" : "2";
    }

    private static byte[] readImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请上传舌象图片");
        }
        try {
            return image.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "图片读取失败，请重新上传");
        }
    }

    private static String resolveMimeType(String storedMimeType, String uploadMimeType, byte[] bytes) {
        if (StringUtils.hasText(storedMimeType) && storedMimeType.startsWith("image/")) {
            return storedMimeType;
        }
        if (StringUtils.hasText(uploadMimeType) && uploadMimeType.startsWith("image/")) {
            return uploadMimeType;
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return "image/jpeg";
    }

    private static boolean isPendingMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        return PENDING_MESSAGES.stream().anyMatch(normalized::contains);
    }

    private static String failureMessage(TongueApiResponse response, String fallback) {
        if (response == null || !StringUtils.hasText(response.message())) {
            return fallback;
        }
        return "舌象检测失败：" + safeMessage(response.message(), "请稍后重试");
    }

    private static String safeMessage(String message, String fallback) {
        if (!StringUtils.hasText(message)) {
            return fallback;
        }
        String value = message.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static BusinessException asBusiness(Exception e, String fallback) {
        if (e instanceof BusinessException businessException) {
            return businessException;
        }
        if (e instanceof TongueClientException) {
            return new BusinessException(ResultCode.ERROR, safeMessage(e.getMessage(), fallback));
        }
        return new BusinessException(ResultCode.ERROR, fallback);
    }

    public record ImageContent(byte[] bytes, String contentType) {
    }
}
