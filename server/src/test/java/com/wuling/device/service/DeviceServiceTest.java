package com.wuling.device.service;

import com.wuling.common.audit.AuditLogService;
import com.wuling.common.exception.BusinessException;
import com.wuling.device.dto.DeviceImportResult;
import com.wuling.device.dto.DeviceUpsert;
import com.wuling.device.entity.DeviceBrewRecord;
import com.wuling.device.entity.DeviceInfo;
import com.wuling.device.entity.DeviceMessageError;
import com.wuling.device.entity.DeviceStatusLog;
import com.wuling.device.entity.DeviceStoreBinding;
import com.wuling.device.mapper.DeviceBrewRecordMapper;
import com.wuling.device.mapper.DeviceInfoMapper;
import com.wuling.device.mapper.DeviceMessageErrorMapper;
import com.wuling.device.mapper.DeviceStatusLogMapper;
import com.wuling.device.mapper.DeviceStoreBindingMapper;
import com.wuling.device.mns.DeviceMessageEnvelope;
import com.wuling.subject.entity.BizSubject;
import com.wuling.subject.mapper.BizSubjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备域服务测试（不依赖数据库，全部走 mock）。
 *
 * <p>覆盖三条最容易出错、且线上代价最高的行为：
 * <ol>
 *   <li>未登记设备的消息不能丢 —— 必须自动建档（未绑定门店）；</li>
 *   <li>MQ 只保证 at-least-once —— 重复投递不能产生重复记录；</li>
 *   <li>绑定/换绑/解绑 —— 历史必须与当前归属一致（换绑要先补旧门店的解绑记录）。</li>
 * </ol>
 */
class DeviceServiceTest {

    private DeviceInfoMapper deviceInfoMapper;
    private DeviceStoreBindingMapper deviceStoreBindingMapper;
    private DeviceBrewRecordMapper deviceBrewRecordMapper;
    private DeviceStatusLogMapper deviceStatusLogMapper;
    private DeviceMessageErrorMapper deviceMessageErrorMapper;
    private BizSubjectMapper bizSubjectMapper;
    private JdbcTemplate jdbcTemplate;
    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        deviceInfoMapper = mock(DeviceInfoMapper.class);
        deviceStoreBindingMapper = mock(DeviceStoreBindingMapper.class);
        deviceBrewRecordMapper = mock(DeviceBrewRecordMapper.class);
        deviceStatusLogMapper = mock(DeviceStatusLogMapper.class);
        deviceMessageErrorMapper = mock(DeviceMessageErrorMapper.class);
        bizSubjectMapper = mock(BizSubjectMapper.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        deviceService = new DeviceService(deviceInfoMapper, deviceStoreBindingMapper, deviceBrewRecordMapper,
                deviceStatusLogMapper, deviceMessageErrorMapper, bizSubjectMapper, jdbcTemplate,
                new AuditLogService(jdbcTemplate));
    }

    // ---------- 队列侧 ----------

    @Test
    void autoRegistersUnknownDeviceAndWritesStatusLogOnChange() {
        when(deviceInfoMapper.selectOne(any())).thenReturn(null);

        deviceService.handleStatusMessage(statusEnvelope("ZPDS2512090086G", "869978072404913",
                "ONLINE", "2026-03-10 19:32:25"));

        ArgumentCaptor<DeviceInfo> created = ArgumentCaptor.forClass(DeviceInfo.class);
        verify(deviceInfoMapper).insert(created.capture());
        assertEquals("ZPDS2512090086G", created.getValue().getDeviceSn());
        assertEquals("869978072404913", created.getValue().getDeviceName());
        assertEquals(1, created.getValue().getEnabled());
        // 未绑定门店：台账先建，归属由运营在后台绑定
        assertNull(created.getValue().getStoreSubjectId());

        verify(deviceStatusLogMapper).insert(any(DeviceStatusLog.class));
        verify(deviceInfoMapper).updateById(any(DeviceInfo.class));
    }

    @Test
    void skipsStatusLogWhenStatusUnchangedButStillRefreshesReportTime() {
        when(deviceInfoMapper.selectOne(any())).thenReturn(device(1L, "SN-1", "ONLINE", null));

        deviceService.handleStatusMessage(statusEnvelope("SN-1", "dev-1", "ONLINE", "2026-03-10 19:32:25"));

        verify(deviceStatusLogMapper, never()).insert(any(DeviceStatusLog.class));
        verify(deviceInfoMapper).updateById(any(DeviceInfo.class));
    }

    @Test
    void duplicateBrewMessageIsSwallowedByUniqueKeyInsteadOfFailingRetryLoop() {
        when(deviceInfoMapper.selectOne(any())).thenReturn(device(1L, "SN-1", "ONLINE", 7L));
        stubStoreSnapshotName(7L, "星沙乐运魔方店");
        when(deviceBrewRecordMapper.insert(any(DeviceBrewRecord.class)))
                .thenThrow(new DuplicateKeyException("uk_device_brew_record"));

        assertDoesNotThrow(() -> deviceService.handleBrewMessage(uploadEnvelope("SN-1")));

        ArgumentCaptor<DeviceBrewRecord> record = ArgumentCaptor.forClass(DeviceBrewRecord.class);
        verify(deviceBrewRecordMapper).insert(record.capture());
        // 门店是写入时刻的快照，设备后续换绑不影响历史记录归属
        assertEquals(7L, record.getValue().getStoreSubjectId());
        assertEquals("星沙乐运魔方店", record.getValue().getStoreName());
        assertEquals("209", record.getValue().getTeaCode());
        assertEquals("408", record.getValue().getSerialNumber());
    }

    @Test
    void brewMessageFromUnboundDeviceIsStoredWithoutStore() {
        when(deviceInfoMapper.selectOne(any())).thenReturn(device(1L, "SN-1", "ONLINE", null));

        deviceService.handleBrewMessage(uploadEnvelope("SN-1"));

        ArgumentCaptor<DeviceBrewRecord> record = ArgumentCaptor.forClass(DeviceBrewRecord.class);
        verify(deviceBrewRecordMapper).insert(record.capture());
        assertNull(record.getValue().getStoreSubjectId());
        assertNull(record.getValue().getStoreName());
    }

    @Test
    void messageErrorIsTruncatedToFitColumn() {
        deviceService.recordMessageError("mid-1", "status", "x".repeat(20_000), "reason");

        ArgumentCaptor<DeviceMessageError> error = ArgumentCaptor.forClass(DeviceMessageError.class);
        verify(deviceMessageErrorMapper).insert(error.capture());
        assertEquals(16_000, error.getValue().getRawBody().length());
    }

    // ---------- 后台绑定 ----------

    @Test
    void rebindWritesUnbindHistoryForOldStoreThenBindHistoryForNewStore() {
        DeviceInfo device = device(10L, "SN-1", "ONLINE", 3L);
        when(deviceInfoMapper.selectById(10L)).thenReturn(device);
        when(bizSubjectMapper.selectById(5L)).thenReturn(store(5L, "新门店"));
        when(bizSubjectMapper.selectById(3L)).thenReturn(store(3L, "旧门店"));

        deviceService.bindStore(10L, 5L, "设备调拨");

        ArgumentCaptor<DeviceStoreBinding> bindings = ArgumentCaptor.forClass(DeviceStoreBinding.class);
        verify(deviceStoreBindingMapper, times(2)).insert(bindings.capture());
        List<DeviceStoreBinding> rows = bindings.getAllValues();
        assertEquals("UNBIND", rows.get(0).getAction());
        assertEquals("旧门店", rows.get(0).getStoreName());
        assertEquals("BIND", rows.get(1).getAction());
        assertEquals("新门店", rows.get(1).getStoreName());

        ArgumentCaptor<DeviceInfo> updated = ArgumentCaptor.forClass(DeviceInfo.class);
        verify(deviceInfoMapper).updateById(updated.capture());
        assertEquals(5L, updated.getValue().getStoreSubjectId());
    }

    @Test
    void rebindToSameStoreIsRejected() {
        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", 5L));
        when(bizSubjectMapper.selectById(5L)).thenReturn(store(5L, "同一家门店"));

        assertThrows(BusinessException.class, () -> deviceService.bindStore(10L, 5L, null));
        verify(deviceStoreBindingMapper, never()).insert(any(DeviceStoreBinding.class));
    }

    @Test
    void unbindClearsCurrentStoreExplicitly() {
        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", 5L));
        when(bizSubjectMapper.selectById(5L)).thenReturn(store(5L, "星沙乐运魔方店"));

        deviceService.unbindStore(10L, "门店撤机");

        ArgumentCaptor<DeviceStoreBinding> binding = ArgumentCaptor.forClass(DeviceStoreBinding.class);
        verify(deviceStoreBindingMapper).insert(binding.capture());
        assertEquals("UNBIND", binding.getValue().getAction());
        assertEquals("门店撤机", binding.getValue().getRemark());

        // MyBatis-Plus 默认忽略 null 字段，置空必须走显式 SQL，否则「解绑点了没反应」
        verify(jdbcTemplate).update(contains("store_subject_id = null"), eq(10L));
        verify(deviceInfoMapper, never()).updateById(any(DeviceInfo.class));
    }

    @Test
    void unbindUnboundDeviceIsRejected() {
        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", null));

        assertThrows(BusinessException.class, () -> deviceService.unbindStore(10L, "noop"));
    }

    @Test
    void bindRejectsNonStoreSubject() {
        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", null));
        BizSubject notStore = store(9L, "某渠道");
        notStore.setSubjectType("CHANNEL");
        when(bizSubjectMapper.selectById(9L)).thenReturn(notStore);

        assertThrows(BusinessException.class, () -> deviceService.bindStore(10L, 9L, null));
    }

    @Test
    void bindingHistoryIsMappedToCamelCaseForFrontend() {
        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", 5L));
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", 1L);
        row.put("action", "BIND");
        row.put("store_subject_id", 5L);
        row.put("store_name", "星沙乐运魔方店");
        row.put("bind_time", java.sql.Timestamp.valueOf("2026-03-10 19:32:25"));
        row.put("unbind_time", null);
        row.put("operator", "admin");
        row.put("remark", "首次绑定");
        row.put("create_time", java.sql.Timestamp.valueOf("2026-03-10 19:32:25"));
        when(jdbcTemplate.queryForList(anyString(), eq(10L))).thenReturn(List.of(row));

        List<java.util.Map<String, Object>> history = deviceService.listBindingHistory(10L);

        assertEquals(1, history.size());
        assertEquals("绑定", history.get(0).get("actionLabel"));
        assertEquals(5L, history.get(0).get("storeSubjectId"));
        assertEquals("2026-03-10 19:32:25", history.get(0).get("createTime"));
    }

    /**
     * V75 遗漏 1（2026-10-14 复核发现）：门店被逻辑删除后设备必须仍能解绑。
     *
     * <p>原实现里 device_store_binding.store_subject_id 是 NOT NULL，
     * 而门店删除后 findSubject 返回 null —— 插库报 NOT NULL 约束、接口 500，
     * 设备挂在已删门店上就再也解绑不了。此处守住「门店为 null 时历史仍写入成功」。
     */
    @Test
    void unbindSurvivesDeletedStoreByKeepingNameSnapshotOnly() {
        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", 5L));
        // 门店已被逻辑删除：selectById 返回 null；门店名快照走 JdbcTemplate 直读
        when(bizSubjectMapper.selectById(5L)).thenReturn(null);
        when(jdbcTemplate.queryForList("select name from biz_subject where id = ?", String.class, 5L))
                .thenReturn(List.of("星沙乐运魔方店"));

        assertDoesNotThrow(() -> deviceService.unbindStore(10L, "门店已删，强制解绑"));

        ArgumentCaptor<DeviceStoreBinding> binding = ArgumentCaptor.forClass(DeviceStoreBinding.class);
        verify(deviceStoreBindingMapper).insert(binding.capture());
        assertNull(binding.getValue().getStoreSubjectId(),
                "门店已删除时只保留 store_name 快照，store_subject_id 必须为 null（否则撞 NOT NULL）");
        assertEquals("SN-1", binding.getValue().getDeviceSn());
        assertEquals("星沙乐运魔方店", binding.getValue().getStoreName(),
                "门店被删后门店名快照仍必须保留，否则绑定历史无法回溯当时挂在哪家门店");
        verify(jdbcTemplate).update(contains("store_subject_id = null"), eq(10L));
    }

    /** 换绑时旧门店已删除，同样不得因 NOT NULL 约束整单失败。 */
    @Test
    void rebindSurvivesDeletedPreviousStore() {
        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", 3L));
        when(bizSubjectMapper.selectById(3L)).thenReturn(null);          // 旧门店已删
        when(jdbcTemplate.queryForList("select name from biz_subject where id = ?", String.class, 3L))
                .thenReturn(List.of("旧门店"));
        when(bizSubjectMapper.selectById(5L)).thenReturn(store(5L, "新门店"));

        assertDoesNotThrow(() -> deviceService.bindStore(10L, 5L, "旧门店已删，直接换绑"));

        ArgumentCaptor<DeviceStoreBinding> bindings = ArgumentCaptor.forClass(DeviceStoreBinding.class);
        verify(deviceStoreBindingMapper, times(2)).insert(bindings.capture());
        assertNull(bindings.getAllValues().get(0).getStoreSubjectId(),
                "旧门店已删除时，UNBIND 历史的 store_subject_id 为 null");
        assertEquals(5L, bindings.getAllValues().get(1).getStoreSubjectId());
        assertEquals("旧门店", bindings.getAllValues().get(0).getStoreName(),
                "旧门店已删除时，UNBIND 历史仍要保留下门店名快照");
    }

    /**
     * 门店名快照必须与列宽（VARCHAR(128)）对齐。
     *
     * <p>biz_subject.name 是 VARCHAR(128)，设备表 store_name 若比它短，
     * 超长门店名会在 insert 时报 Data too long 并回滚整个事务 ——
     * 冲泡记录会反复重试进死信队列。这里守住「写入前先截断」。
     */
    @Test
    void storeNameSnapshotIsTruncatedToColumnWidth() {
        String longName = "长".repeat(200);
        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", 5L));
        when(bizSubjectMapper.selectById(5L)).thenReturn(store(5L, longName));

        deviceService.unbindStore(10L, "长门店名");

        ArgumentCaptor<DeviceStoreBinding> binding = ArgumentCaptor.forClass(DeviceStoreBinding.class);
        verify(deviceStoreBindingMapper).insert(binding.capture());
        assertEquals(128, binding.getValue().getStoreName().length(),
                "门店名快照必须截断到 128 字符，否则超长门店名会让整条消费链路失败");
    }

    /** 冲泡记录的门店名快照同样要截断（消费链路写入点）。 */
    @Test
    void brewRecordStoreNameSnapshotIsTruncatedToColumnWidth() {
        String longName = "长".repeat(200);
        when(deviceInfoMapper.selectOne(any())).thenReturn(device(10L, "SN-1", "ONLINE", 5L));
        stubStoreSnapshotName(5L, longName);

        deviceService.handleBrewMessage(uploadEnvelope("SN-1"));

        ArgumentCaptor<DeviceBrewRecord> record = ArgumentCaptor.forClass(DeviceBrewRecord.class);
        verify(deviceBrewRecordMapper).insert(record.capture());
        assertEquals(128, record.getValue().getStoreName().length(),
                "冲泡记录的门店名快照必须截断到 128 字符");
    }
    /**
     * 打桩「门店名快照」的读取。
     *
     * <p>设备域有两条读门店名的路径，语义不同、必须分别打桩：
     * <ul>
     *   <li>DTO 用的实时门店名 → {@code BizSubjectMapper.selectById}
     *       （带 {@code @TableLogic}，门店被删就查不到）；</li>
     *   <li>写库用的门店名快照 → {@code JdbcTemplate} 直读 biz_subject
     *       （绕过 {@code @TableLogic}，门店删除后仍要能回溯「当时挂在哪家门店」）。</li>
     * </ul>
     */
    private void stubStoreSnapshotName(long subjectId, String name) {
        when(bizSubjectMapper.selectById(subjectId)).thenReturn(store(subjectId, name));
        when(jdbcTemplate.queryForList("select name from biz_subject where id = ?", String.class, subjectId))
                .thenReturn(List.of(name));
    }
    /** 删除台账：仍绑定门店时拒绝；解绑后软删。 */
    @Test
    void deleteDeviceRequiresUnbindThenSoftDeletes() {
        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", 5L));
        assertThrows(BusinessException.class, () -> deviceService.deleteDevice(10L, "仍在服役"));

        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", null));
        when(jdbcTemplate.update(contains("set deleted = 1"), eq(10L))).thenReturn(1);
        assertDoesNotThrow(() -> deviceService.deleteDevice(10L, "换机下线"));
        verify(jdbcTemplate).update(contains("set deleted = 1"), eq(10L));
    }

    @Test
    void deleteDeviceRequiresReason() {
        DeviceInfo unbound = device(10L, "SN-1", "ONLINE", null);
        when(deviceInfoMapper.selectById(10L)).thenReturn(unbound);

        assertThrows(BusinessException.class, () -> deviceService.deleteDevice(10L, "  "));

        verify(jdbcTemplate, never()).update(contains("set deleted = 1"), eq(10L));
    }

    @Test
    void createDeviceRequiresDeviceName() {
        DeviceUpsert upsert = upsert("SN-NEW", null);

        assertThrows(BusinessException.class, () -> deviceService.createDevice(upsert));

        verify(deviceInfoMapper, never()).insert(any(DeviceInfo.class));
    }

    @Test
    void importDevicesRejectsBatchDuplicatesWithoutWriting() {
        DeviceImportResult result = deviceService.importDevices(List.of(
                upsert("SN-DUP", "ID-DUP"),
                upsert("SN-DUP", "ID-OTHER")));

        assertFalse(result.isAccepted());
        assertEquals(0, result.getImported());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("设备编码重复")));
        verify(deviceInfoMapper, never()).insert(any(DeviceInfo.class));
    }

    @Test
    void importDevicesRejectsActiveDeviceNameWithoutWriting() {
        when(deviceInfoMapper.selectOne(any())).thenReturn(null);
        when(jdbcTemplate.queryForList(contains("device_name = ? and deleted = 0"),
                eq(Long.class), eq("ID-ACTIVE"))).thenReturn(List.of(99L));

        DeviceImportResult result = deviceService.importDevices(List.of(upsert("SN-NEW", "ID-ACTIVE")));

        assertFalse(result.isAccepted());
        assertEquals(0, result.getImported());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("设备ID已存在")));
        verify(deviceInfoMapper, never()).insert(any(DeviceInfo.class));
    }

    @Test
    void updateDeviceRejectsActiveDeviceName() {
        when(deviceInfoMapper.selectById(10L)).thenReturn(device(10L, "SN-1", "ONLINE", null));
        when(deviceInfoMapper.selectOne(any())).thenReturn(device(10L, "SN-1", "ONLINE", null));
        when(jdbcTemplate.queryForList(contains("device_name = ? and deleted = 0"),
                eq(Long.class), eq("ID-OTHER"))).thenReturn(List.of(11L));

        assertThrows(BusinessException.class,
                () -> deviceService.updateDevice(10L, upsert("SN-1", "ID-OTHER")));

        verify(deviceInfoMapper, never()).updateById(any(DeviceInfo.class));
    }

    @Test
    void createDeviceRejectsNonAsciiIdentityBeforeDatabaseLookup() {
        DeviceUpsert upsert = upsert("SN' OR 1=1 --", "ID-1");

        assertThrows(BusinessException.class, () -> deviceService.createDevice(upsert));

        verify(deviceInfoMapper, never()).selectOne(any());
        verify(deviceInfoMapper, never()).insert(any(DeviceInfo.class));
    }

    @Test
    void createDeviceRejectsControlCharactersInRemark() {
        DeviceUpsert upsert = upsert("SN-OK", "ID-OK");
        upsert.setRemark("bad\u0001remark");

        assertThrows(BusinessException.class, () -> deviceService.createDevice(upsert));

        verify(deviceInfoMapper, never()).insert(any(DeviceInfo.class));
    }

    @Test
    void importDevicesRejectsMoreThanMaxRowsBeforeDatabaseLookup() {
        List<DeviceUpsert> rows = new ArrayList<>();
        for (int i = 0; i < 5001; i++) {
            rows.add(upsert("SN-" + i, "ID-" + i));
        }

        DeviceImportResult result = deviceService.importDevices(rows);

        assertFalse(result.isAccepted());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("最多 5000 行")));
        verify(deviceInfoMapper, never()).selectOne(any());
        verify(deviceInfoMapper, never()).insert(any(DeviceInfo.class));
    }

    /**
     * 已被删除的台账不再被上报「复活」：只落事实数据，不重建台账。
     *
     * <p>否则运营删掉的设备会因为还在上报而自己回到列表里，删除动作形同虚设。
     */
    @Test
    void reportFromDeletedLedgerDoesNotResurrectDevice() {
        when(deviceInfoMapper.selectOne(any())).thenReturn(null);
        when(jdbcTemplate.queryForList(contains("deleted = 1"), eq(Long.class), eq("SN-1")))
                .thenReturn(List.of(9L));

        assertDoesNotThrow(() -> deviceService.handleBrewMessage(uploadEnvelope("SN-1")));

        verify(deviceInfoMapper, never()).insert(any(DeviceInfo.class));
        verify(deviceInfoMapper, never()).updateById(any(DeviceInfo.class));
        ArgumentCaptor<DeviceBrewRecord> record = ArgumentCaptor.forClass(DeviceBrewRecord.class);
        verify(deviceBrewRecordMapper).insert(record.capture());
        assertEquals("SN-1", record.getValue().getDeviceSn());
        assertNull(record.getValue().getStoreSubjectId(), "台账已删除时记录不归属门店");
    }
    /**
     * 旧状态不得覆盖新状态（MQ 乱序）。
     *
     * <p>MQ 是 at-least-once，重试/重复投递会让更早的状态报文晚到；
     * 以「当前状态的确立时间」为水位线，比它更早的报文整条丢弃：
     * 不改状态、不改状态确立时间、也不写流水。
     */
    @Test
    void staleStatusMessageDoesNotOverwriteNewerState() {
        DeviceInfo current = device(1L, "SN-1", "OFFLINE", null);
        current.setLastStatusChangeTime(LocalDateTime.parse("2026-03-10T20:00:00"));
        when(deviceInfoMapper.selectOne(any())).thenReturn(current);

        deviceService.handleStatusMessage(statusEnvelope("SN-1", "dev-1", "ONLINE", "2026-03-10 19:00:00"));

        verify(deviceStatusLogMapper, never()).insert(any(DeviceStatusLog.class));
        verify(deviceInfoMapper, never()).updateById(any(DeviceInfo.class));
    }
    // ---------- 构造样本 ----------

    private static DeviceMessageEnvelope statusEnvelope(String deviceSn, String deviceName,
                                                       String status, String reportTime) {
        DeviceMessageEnvelope envelope = new DeviceMessageEnvelope();
        envelope.setType(DeviceMessageEnvelope.TYPE_STATUS);
        envelope.setDeviceSn(deviceSn);
        envelope.setDeviceName(deviceName);
        envelope.setOnlineStatus(status);
        envelope.setReportTime(reportTime);
        return envelope;
    }

    private static DeviceMessageEnvelope uploadEnvelope(String deviceSn) {
        DeviceMessageEnvelope envelope = new DeviceMessageEnvelope();
        envelope.setType(DeviceMessageEnvelope.TYPE_UPLOAD);
        envelope.setDeviceSn(deviceSn);
        envelope.setDeviceName("869978072404913");
        envelope.setTeaCode("209");
        envelope.setTeaName("欧米伽-3植物乳");
        envelope.setCustomerCode("90000001");
        envelope.setMadeDay("20251021");
        envelope.setSerialNumber("408");
        envelope.setReportTime("2026-03-10 21:16:04");
        envelope.setRawPayload("{\"deviceSN\":\"" + deviceSn + "\"}");
        return envelope;
    }

    private static DeviceUpsert upsert(String deviceSn, String deviceName) {
        DeviceUpsert upsert = new DeviceUpsert();
        upsert.setDeviceSn(deviceSn);
        upsert.setDeviceName(deviceName);
        upsert.setEnabled(1);
        return upsert;
    }

    private static DeviceInfo device(Long id, String deviceSn, String onlineStatus, Long storeSubjectId) {
        DeviceInfo device = new DeviceInfo();
        device.setId(id);
        device.setDeviceSn(deviceSn);
        device.setDeviceName("869978072404913");
        device.setEnabled(1);
        device.setOnlineStatus(onlineStatus);
        device.setStoreSubjectId(storeSubjectId);
        return device;
    }

    private static BizSubject store(Long id, String name) {
        BizSubject subject = new BizSubject();
        subject.setId(id);
        subject.setName(name);
        subject.setSubjectType("STORE");
        return subject;
    }
}
