package com.wuling.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.redis.RedisManager;
import com.wuling.common.redis.RedisNamespace;
import com.wuling.user.dto.tongue.TongueDetectionResponse;
import com.wuling.user.dto.tongue.TongueReportDetailResponse;
import com.wuling.user.entity.TongueReport;
import com.wuling.user.mapper.TongueReportMapper;
import com.wuling.user.dto.tongue.TongueProductItem;
import com.wuling.user.dto.tongue.TongueReportSummaryResponse;
import com.wuling.user.tongue.ProductCard;
import com.wuling.user.tongue.ProductCardClient;
import com.wuling.user.tongue.TongueApiResponse;
import com.wuling.user.tongue.TongueClientException;
import com.wuling.user.tongue.TongueDetectionClient;
import com.wuling.user.tongue.TongueFileClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TongueReportServiceTest {

    private TongueReportMapper mapper;
    private TongueFileClient fileClient;
    private TongueDetectionClient detectionClient;
    private ProductCardClient productCardClient;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private TongueReportService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(TongueReportMapper.class);
        fileClient = mock(TongueFileClient.class);
        detectionClient = mock(TongueDetectionClient.class);
        RedisManager redisManager = mock(RedisManager.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);

        productCardClient = mock(ProductCardClient.class);
        when(redisManager.template(RedisNamespace.BIZ)).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(productCardClient.cardsByNames(any())).thenReturn(Map.of());
        service = new TongueReportService(mapper, fileClient, detectionClient, redisManager, productCardClient);
    }

    @ParameterizedTest
    @CsvSource({"male,1", "female,2"})
    @DisplayName("检测请求把小程序 male/female 映射为第三方 1/2")
    void detectMapsMiniAppSex(String miniAppSex, String thirdPartySex) throws Exception {
        MultipartFile image = image();
        when(fileClient.upload(image)).thenReturn(new TongueFileClient.StoredFile("store-1", "image/png", 3L));
        when(detectionClient.detect(eq(thirdPartySex), eq(30), contains("data:image/png;base64,YWJj")))
                .thenReturn(api(true, "0", "ok", "0", """
                        {"reportId":"report-1","physique":"平和质","summarize":"总体良好"}
                        """));
        when(mapper.insert(any(TongueReport.class))).thenAnswer(invocation -> {
            TongueReport report = invocation.getArgument(0);
            report.setId(10L);
            return 1;
        });

        TongueDetectionResponse response = service.detect(1L, image, 30, miniAppSex);

        assertEquals("PENDING", response.reportStatus(),
                "detection 只有摘要时仍应等待 getReport 详情");
        verify(detectionClient).detect(eq(thirdPartySex), eq(30), contains("data:image/png;base64,YWJj"));
    }

    @Test
    @DisplayName("检测成功后保存报告并返回小程序安全字段")
    void detectPersistsReadyReportAndReturnsSafeView() throws Exception {
        MultipartFile image = image();
        when(fileClient.upload(image)).thenReturn(new TongueFileClient.StoredFile("store-1", "image/png", 3L));
        when(detectionClient.detect(eq("1"), eq(28), contains("data:image/png;base64,YWJj")))
                .thenReturn(api(true, "0", "ok", "0", """
                        {
                          "reportId":"report-1",
                          "physique":"平和质",
                          "summarize":"总体良好",
                          "reportStatus":"READY",
                          "productList":[{"goodsId":"G-1","goodsName":"陈皮茶"}],
                          "children":[{"id":"g1","title":"概况","children":[{"id":"c1","title":"舌质","content":"淡红"}]}]
                        }
                        """));
        when(mapper.insert(any(TongueReport.class))).thenAnswer(invocation -> {
            TongueReport report = invocation.getArgument(0);
            report.setId(10L);
            return 1;
        });

        TongueDetectionResponse response = service.detect(1L, image, 28, "male");

        assertEquals(10L, response.id());
        assertEquals("平和质", response.physique());
        assertEquals("总体良好", response.summarize());
        assertEquals("READY", response.reportStatus());
        assertEquals("G-1", response.productList().get(0).goodsId());
        assertEquals("概况", response.reportGroups().get(0).title());
        assertEquals("舌质", response.reportGroups().get(0).sections().get(0).title());
        assertTrue(response.hasImage());
        var responseFields = java.util.Arrays.stream(response.getClass().getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();
        assertFalse(responseFields.contains("reportId"));
        assertFalse(responseFields.contains("imageStorageName"));
        assertFalse(responseFields.contains("storageName"));

        var captor = org.mockito.ArgumentCaptor.forClass(TongueReport.class);
        verify(mapper).insert(captor.capture());
        TongueReport saved = captor.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals("male", saved.getSex());
        assertEquals(28, saved.getAge());
        assertEquals("store-1", saved.getImageStorageName());
        assertTrue(saved.getReportJson().contains("总体良好"));
        assertTrue(saved.getProductListJson().contains("G-1"));
    }

    @Test
    @DisplayName("检测返回 pending 时保存 PENDING 并可稍后刷新")
    void detectPersistsPendingStatus() throws Exception {
        MultipartFile image = image();
        when(fileClient.upload(image)).thenReturn(new TongueFileClient.StoredFile("store-1", "image/png", 3L));
        when(detectionClient.detect(anyString(), eq(30), anyString()))
                .thenReturn(api(true, "0", "ok", "0", """
                        {"reportId":"report-pending","reportStatus":"PROCESSING"}
                        """));
        when(mapper.insert(any(TongueReport.class))).thenAnswer(invocation -> {
            TongueReport report = invocation.getArgument(0);
            report.setId(11L);
            return 1;
        });

        TongueDetectionResponse response = service.detect(1L, image, 30, "female");

        assertEquals("PENDING", response.reportStatus());
        var captor = org.mockito.ArgumentCaptor.forClass(TongueReport.class);
        verify(mapper).insert(captor.capture());
        assertEquals("PENDING", captor.getValue().getReportStatus());
    }

    @Test
    @DisplayName("同一第三方 reportId 重试时复用既有报告并删除新图片")
    void detectReusesExistingReportForDuplicateReportId() throws Exception {
        MultipartFile image = image();
        when(fileClient.upload(image)).thenReturn(new TongueFileClient.StoredFile("store-new", "image/png", 3L));
        when(detectionClient.detect(anyString(), eq(30), anyString()))
                .thenReturn(api(true, "0", "ok", "0", """
                        {"reportId":"report-1","physique":"平和质","summarize":"总体良好"}
                        """));
        TongueReport existing = report(9L, 1L, "report-1", "PENDING");
        existing.setPhysique("平和质");
        existing.setDeleted(0);
        when(mapper.selectByReportId("report-1")).thenReturn(existing);

        TongueDetectionResponse response = service.detect(1L, image, 30, "male");

        assertEquals(9L, response.id());
        assertEquals("平和质", response.physique());
        verify(fileClient).delete("store-new");
        verify(mapper, never()).insert(any(TongueReport.class));
    }

    @Test
    @DisplayName("第三方检测失败时补偿删除已上传图片且不落库")
    void detectFailureCompensatesUploadedImage() {
        MultipartFile image = image();
        when(fileClient.upload(image)).thenReturn(new TongueFileClient.StoredFile("store-1", "image/png", 3L));
        when(detectionClient.detect(anyString(), eq(30), anyString()))
                .thenThrow(new TongueClientException("上游异常"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.detect(1L, image, 30, "male"));

        assertEquals(ResultCode.ERROR, error.getCode());
        verify(fileClient).delete("store-1");
        verify(mapper, never()).insert(any(TongueReport.class));
    }

    @Test
    @DisplayName("列表查询只取本人最近 6 个月且未删除记录")
    void listRecentUsesSixMonthWindow() {
        TongueReport report = report(9L, 1L, "report-1", "PENDING");
        when(mapper.selectRecentByUser(eq(1L), any(LocalDateTime.class))).thenReturn(List.of(report));

        var result = service.listRecent(1L);

        assertEquals(1, result.size());
        var captor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).selectRecentByUser(eq(1L), captor.capture());
        LocalDateTime expected = LocalDateTime.now().minusMonths(6);
        assertFalse(captor.getValue().isBefore(expected.minusSeconds(10)));
        assertFalse(captor.getValue().isAfter(expected.plusSeconds(10)));
    }

    @Test
    @DisplayName("详情访问他人记录按不存在处理，避免泄露记录归属")
    void detailRejectsOtherUsersReport() {
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.detail(1L, 9L));

        assertEquals(ResultCode.NOT_FOUND, error.getCode());
    }

    @Test
    @DisplayName("删除仅做逻辑删除，不删除文件与行")
    void deleteOnlyLogicallyDeletesOwnedReport() {
        TongueReport report = report(9L, 1L, "report-1", "READY");
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);

        service.delete(1L, 9L);

        verify(mapper).deleteById(9L);
        verify(fileClient, never()).delete(anyString());
    }

    @Test
    @DisplayName("refresh 对同一用户 10 秒内只允许一次第三方查询")
    void refreshRateLimitsPerUser() {
        TongueReport report = report(9L, 1L, "report-1", "PENDING");
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(10)))).thenReturn(false);

        BusinessException error = assertThrows(BusinessException.class, () -> service.refresh(1L, 9L));

        assertEquals(ResultCode.BAD_REQUEST, error.getCode());
        verify(detectionClient, never()).fetchReport(anyString());
    }

    @Test
    @DisplayName("第三方返回数据不存在时保持 PENDING")
    void refreshKeepsPendingWhenReportDoesNotExistYet() throws Exception {
        TongueReport report = report(9L, 1L, "report-1", "PENDING");
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(10)))).thenReturn(true);
        when(detectionClient.fetchReport("report-1"))
                .thenReturn(api(false, null, "数据不存在", null, null));

        TongueReportDetailResponse response = service.refresh(1L, 9L);

        assertEquals("PENDING", response.reportStatus());
        var captor = org.mockito.ArgumentCaptor.forClass(TongueReport.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("PENDING", captor.getValue().getReportStatus());
    }

    @Test
    @DisplayName("第三方其他失败标记 FAILED 并返回可理解错误")
    void refreshMarksFailedForOtherErrors() throws Exception {
        TongueReport report = report(9L, 1L, "report-1", "PENDING");
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(10)))).thenReturn(true);
        when(detectionClient.fetchReport("report-1"))
                .thenReturn(api(false, null, "参数错误", null, null));

        BusinessException error = assertThrows(BusinessException.class, () -> service.refresh(1L, 9L));

        assertEquals(ResultCode.ERROR, error.getCode());
        assertTrue(error.getMessage().contains("参数错误"));
        var captor = org.mockito.ArgumentCaptor.forClass(TongueReport.class);
        verify(mapper).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getReportStatus());
    }

    @Test
    @DisplayName("READY 记录刷新直接返回，不调用第三方也不占限流")
    void refreshReadyReturnsDirectly() {
        TongueReport report = report(9L, 1L, "report-1", "READY");
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);

        TongueReportDetailResponse response = service.refresh(1L, 9L);

        assertEquals("READY", response.reportStatus());
        verify(detectionClient, never()).fetchReport(anyString());
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("本人图片读取返回 file-service 的 bytes 与 Content-Type")
    void imageLoadsOwnedBytesAndMimeType() {
        TongueReport report = report(9L, 1L, "report-1", "READY");
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);
        when(fileClient.download("store-1"))
                .thenReturn(new TongueFileClient.ImageBytes(new byte[]{1, 2, 3}, "image/png"));

        TongueReportService.ImageContent image = service.image(1L, 9L);

        assertEquals("image/png", image.contentType());
        assertEquals(3, image.bytes().length);
    }

    @Test
    @DisplayName("详情按商品名匹配商城商品，补全图片、价格与标签")
    void detailEnrichesMatchedProducts() {
        TongueReport report = report(9L, 1L, "report-1", "READY");
        report.setProductListJson("""
                [{"goodsId":"G-1","goodsName":"桂香暖润茶","reason":"【推荐理由】 健脾祛湿"}]
                """);
        report.setReportJson("""
                {"children":[{"id":"2","title":"饮食","children":[{"id":"2.6","title":"推荐商品","children":[
                {"id":"2.6.1","title":"桂香暖润茶","descriptions":[{"title":"【推荐理由】","content":"健脾祛湿"}]}]}]}]}
                """);
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);
        when(productCardClient.cardsByNames(any())).thenReturn(Map.of(
                "桂香暖润茶",
                new ProductCard("herbal-001", "桂香暖润茶", "/assets/images/3x/menu-product.jpg",
                        1290L, 1500L, List.of("草本养生"), List.of("痰湿质"))));

        TongueReportDetailResponse response = service.detail(1L, 9L);

        TongueProductItem product = response.productList().get(0);
        assertTrue(product.matched(), "命中商城商品后 matched 必须为 true");
        assertEquals("herbal-001", product.productId());
        assertEquals("/assets/images/3x/menu-product.jpg", product.image());
        assertEquals(1290L, product.price());
        assertEquals(1500L, product.originalPrice());
        assertEquals(List.of("草本养生"), product.tags());
        assertEquals(List.of("痰湿质"), product.suitableConstitutions(), "详情必须下发适宜体质供卡片展示");
        assertEquals("diet", response.reportGroups().get(0).tabKey(),
                "饮食分组必须映射为 diet，供小程序详情页 Tab 使用");
        TongueProductItem grouped = response.reportGroups().get(0).products().get(0);
        assertTrue(grouped.matched(), "分组内同名商品同样要补全");
        assertEquals("herbal-001", grouped.productId());
    }

    @Test
    @DisplayName("商城匹配不到的报告商品按未命中返回，不伪造价格与图片")
    void detailKeepsUnmatchedProducts() {
        TongueReport report = report(9L, 1L, "report-1", "READY");
        report.setProductListJson("""
                [{"goodsId":"G-1","goodsName":"滇绿","reason":"【推荐理由】 清热解毒"}]
                """);
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);

        TongueReportDetailResponse response = service.detail(1L, 9L);

        TongueProductItem product = response.productList().get(0);
        assertFalse(product.matched());
        assertFalse(product.productId() != null && !product.productId().isBlank());
        assertEquals(null, product.image());
        assertEquals(null, product.price());
        assertEquals("滇绿", product.goodsName());
    }

    @Test
    @DisplayName("商品服务降级为空结果时报告仍正常返回")
    void detailSurvivesProductServiceOutage() {
        TongueReport report = report(9L, 1L, "report-1", "READY");
        report.setProductListJson("""
                [{"goodsId":"G-1","goodsName":"桂香暖润茶"}]
                """);
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);
        when(productCardClient.cardsByNames(any())).thenReturn(Map.of());

        TongueReportDetailResponse response = service.detail(1L, 9L);

        assertEquals("READY", response.reportStatus());
        assertEquals(1, response.productList().size());
        assertFalse(response.productList().get(0).matched());
    }

    @Test
    @DisplayName("列表与详情下发该次检测的性别年龄")
    void listAndDetailExposeDetectionSexAndAge() {
        TongueReport report = report(9L, 1L, "report-1", "READY");
        report.setSex("female");
        report.setAge(31);
        when(mapper.selectRecentByUser(eq(1L), any(LocalDateTime.class))).thenReturn(List.of(report));
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);

        List<TongueReportSummaryResponse> list = service.listRecent(1L);
        TongueReportDetailResponse detail = service.detail(1L, 9L);

        assertEquals("female", list.get(0).sex(), "记录列表必须下发检测时的性别");
        assertEquals(31, list.get(0).age(), "记录列表必须下发检测时的年龄");
        assertEquals("female", detail.sex(), "详情必须下发检测时的性别");
        assertEquals(31, detail.age(), "详情必须下发检测时的年龄");
    }

    @Test
    @DisplayName("老记录没有性别年龄时下发 null，不抛异常")
    void oldReportWithoutSexAndAgeReturnsNulls() {
        TongueReport report = report(9L, 1L, "report-1", "READY");
        when(mapper.selectOwnedRecent(eq(9L), eq(1L), any(LocalDateTime.class))).thenReturn(report);

        TongueReportDetailResponse detail = service.detail(1L, 9L);

        assertNull(detail.sex());
        assertNull(detail.age());
    }

    private static MultipartFile image() {
        return new MockMultipartFile("image", "tongue.png", "image/png", new byte[]{'a', 'b', 'c'});
    }

    private static TongueReport report(Long id, Long userId, String reportId, String status) {
        TongueReport report = new TongueReport();
        report.setId(id);
        report.setUserId(userId);
        report.setReportId(reportId);
        report.setImageStorageName("store-1");
        report.setReportStatus(status);
        report.setReportJson("{}");
        report.setProductListJson("[]");
        report.setCreateTime(LocalDateTime.now().minusDays(1));
        report.setUpdateTime(LocalDateTime.now().minusDays(1));
        return report;
    }

    private static TongueApiResponse api(boolean success, String status, String message,
                                         String code, String dataJson) throws Exception {
        return new TongueApiResponse(success, status, message, code,
                dataJson == null ? null : new ObjectMapper().readTree(dataJson));
    }
}
