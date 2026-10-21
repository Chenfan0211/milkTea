package com.wuling.device;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.wuling.common.api.PageResult;
import com.wuling.common.audit.AuditLogService;
import com.wuling.common.cache.AppConfigCacheService;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.device.dto.AdminDeviceDTO;
import com.wuling.device.dto.DeviceImportResult;
import com.wuling.device.dto.DeviceUpsert;
import com.wuling.device.mapper.DeviceBrewRecordMapper;
import com.wuling.device.mapper.DeviceInfoMapper;
import com.wuling.device.mapper.DeviceMessageErrorMapper;
import com.wuling.device.mapper.DeviceStatusLogMapper;
import com.wuling.device.mapper.DeviceStoreBindingMapper;
import com.wuling.device.mns.DeviceMessageEnvelope;
import com.wuling.device.mns.MnsMessageParser;
import com.wuling.device.mns.MnsParseException;
import com.wuling.device.mq.DeviceMessageConsumer;
import com.wuling.device.service.DeviceService;
import com.wuling.security.AdminUser;
import com.wuling.subject.mapper.BizSubjectMapper;
import com.wuling.system.crud.CrudService;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 茶机设备消息「全链路」端到端测试（慧养智萃茶机对接）。
 *
 * <p><b>为什么要有这个测试</b>：
 * {@code DeviceServiceTest} / {@code MnsMessageParserTest} 全部以 mock 或纯函数方式验证，
 * 覆盖不到只有「真 SQL」才会暴露的一类问题：
 * <ul>
 *   <li>实体字段 ↔ 迁移脚本建表列的漂移（mock 掉 mapper 后永远是绿的）；</li>
 *   <li>唯一键去重是否真的建在表上、真能拦住重复投递；</li>
 *   <li>通用 CRUD 只读资源（deviceBrewRecords）的白名单/驼峰转换在真 SQL 下是否成立；</li>
 *   <li>门店快照、换绑历史、门店已删除时解绑等边界（V77 修的就是这类）。</li>
 * </ul>
 *
 * <p><b>链路</b>：对方报文（Base64 + 双层 JSON）→ {@link MnsMessageParser} →
 * 序列化成 MQ 消息体 → {@link DeviceMessageConsumer}（幂等/ACK 走真实基类）→
 * {@link DeviceService} → 真实 SQL 落库 → 后台「设备管理 / 冲泡记录」查询读回。
 *
 * <p><b>环境</b>：H2 内存库（MySQL 兼容模式）跑<b>真实迁移脚本 V75</b>，
 * 不依赖 Docker / MySQL / RabbitMQ / MNS，CI 可直接跑。
 */
class DeviceFlowEndToEndTest {

    private static final String SN = "ZPDS2512090086G";
    private static final String DEVICE_ID = "869978072404913";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MnsMessageParser PARSER = new MnsMessageParser();

    private JdbcTemplate jdbc;
    private DeviceService deviceService;
    private DeviceMessageConsumer consumer;
    private CrudService crudService;
    private MqIdempotent idempotent;
    private Channel channel;
    private SqlSession session;
    private long storeA;
    private long storeB;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:device-flow-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            // === 真实迁移脚本直接跑 ===
            // Entity ↔ DDL 列漂移、唯一键是否真的存在，只有跑真脚本才能发现。
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V75__device_management.sql"));

            // V77 是 MySQL 动态 SQL（information_schema + PREPARE/EXECUTE），H2 无法执行，
            // 这里等价重放它真正的两条口径变更，保证测试库与生产库结构一致：
            //   ① device_store_binding.store_subject_id 允许 NULL（门店已删除时解绑/换绑仍要留痕）
            //   ② 三张表的 store_name 放宽到 128（与 biz_subject.name 同宽）
            alter(connection, "alter table device_store_binding alter column store_subject_id drop not null");
            alter(connection, "alter table device_store_binding alter column store_name set data type varchar(128)");
            alter(connection, "alter table device_brew_record alter column store_name set data type varchar(128)");
            alter(connection, "alter table device_status_log alter column store_name set data type varchar(128)");

            // V78（状态流水补 raw_payload 留档）同样是 MySQL 动态 SQL，H2 无法执行，等价重放。
            // 注意：后续设备类迁移每新增一列，这里都要同步补 —— 漏了会直接报 Column not found，测试即红。
            alter(connection, "alter table device_status_log add column raw_payload text null");

            // 设备域只读 biz_subject 的既有列，这里按真实表结构（V1 schema_core）取子集建表，
            // 故意只建 DeviceService 实际用到的列，避免测试与业务表强耦合。
            try (Statement st = connection.createStatement()) {
                st.execute("create table biz_subject ("
                        + "id bigint not null auto_increment,"
                        + "code varchar(64) not null,"
                        + "name varchar(128) not null,"
                        + "subject_type varchar(32) not null,"
                        + "status varchar(32),"
                        + "bound_user_id bigint,"
                        + "create_time timestamp not null default current_timestamp,"
                        + "update_time timestamp not null default current_timestamp,"
                        + "deleted tinyint not null default 0,"
                        + "primary key (id))");
                st.execute("insert into biz_subject (code,name,subject_type,status,deleted) "
                        + "values ('S1','星沙乐运魔方店','STORE','enabled',0)");
                st.execute("insert into biz_subject (code,name,subject_type,status,deleted) "
                        + "values ('S2','长沙国金中心店','STORE','enabled',0)");

                // audit_log：设备域只用到 AuditLogService.recordChange 的这几列，
                // 按真实表（V1 schema_core）取子集建表，供「删除原因进库」断言使用。
                st.execute("create table audit_log ("
                        + "id bigint not null auto_increment,"
                        + "operator varchar(64),"
                        + "module varchar(64),"
                        + "action varchar(64),"
                        + "target varchar(255),"
                        + "before_value text,"
                        + "after_value text,"
                        + "reason varchar(255),"
                        + "ip varchar(64),"
                        + "create_time timestamp not null default current_timestamp,"
                        + "primary key (id))");
            }
        }

        jdbc = new JdbcTemplate(dataSource);
        storeA = jdbc.queryForObject("select id from biz_subject where code = 'S1'", Long.class);
        storeB = jdbc.queryForObject("select id from biz_subject where code = 'S2'", Long.class);

        // MyBatis-Plus 不走 Spring 容器，这里显式注册 6 个 mapper，
        // 保证 @TableName / @TableId 等 MP 语义在测试里与线上一致（不是手搓 JDBC）。
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(DeviceInfoMapper.class);
        configuration.addMapper(DeviceStoreBindingMapper.class);
        configuration.addMapper(DeviceBrewRecordMapper.class);
        configuration.addMapper(DeviceStatusLogMapper.class);
        configuration.addMapper(DeviceMessageErrorMapper.class);
        configuration.addMapper(BizSubjectMapper.class);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.afterPropertiesSet();
        SqlSessionFactory factory = factoryBean.getObject();
        // 必须用 SqlSessionTemplate（而不是 openSession()）：
        // 只有它才会把 JDBC 约束冲突翻译成 Spring 的 DuplicateKeyException，
        // 与生产（@MapperScan + MP 的 SqlSessionTemplate）口径一致。
        // 用裸 session 会因为异常类型不同，让「唯一键去重」在测试里假绿。
        session = new SqlSessionTemplate(factory);

        deviceService = new DeviceService(
                session.getMapper(DeviceInfoMapper.class),
                session.getMapper(DeviceStoreBindingMapper.class),
                session.getMapper(DeviceBrewRecordMapper.class),
                session.getMapper(DeviceStatusLogMapper.class),
                session.getMapper(DeviceMessageErrorMapper.class),
                session.getMapper(BizSubjectMapper.class),
                jdbc,
                new AuditLogService(jdbc));

        idempotent = mock(MqIdempotent.class);
        when(idempotent.tryAcquire(anyString())).thenReturn(true);
        consumer = new DeviceMessageConsumer(idempotent, deviceService);
        channel = mock(Channel.class);
        crudService = new CrudService(jdbc, mock(AppConfigCacheService.class));
    }

    @AfterEach
    void tearDown() {
        AdminUser.clear();

    }

    @Test
    @DisplayName("状态链路：报文 → 解析 → 消费 → 自动建档（未绑定门店）→ 状态流水")
    void statusMessageBuildsDeviceLedgerAndStatusLog() throws Exception {
        deliver(mnsBody("status", statusPayload("ONLINE", "2026-03-10 19:32:25")), "sim-status-1");

        Map<String, Object> device = jdbc.queryForMap(
                "select device_sn, device_name, online_status, cast(last_report_time as varchar) as last_report_time, "
                        + "cast(last_status_change_time as varchar) as last_status_change_time, "
                        + "store_subject_id, enabled from device_info where device_sn = ?", SN);
        assertEquals(SN, device.get("device_sn"));
        assertEquals(DEVICE_ID, device.get("device_name"));
        assertEquals("ONLINE", device.get("online_status"));
        assertEquals("2026-03-10 19:32:25", String.valueOf(device.get("last_report_time")));
        // 报文里没有门店字段：只能自动建档为「未绑定」，归属由运营在后台绑定
        assertNull(device.get("store_subject_id"));
        assertEquals(1, ((Number) device.get("enabled")).intValue());

        assertEquals(1L, count("select count(*) from device_status_log where device_sn = ?", SN));
        // 状态流水同样留档原始 payload（V78）：对方给 status 报文加字段后可回溯
        assertTrue(String.valueOf(jdbc.queryForObject(
                        "select cast(raw_payload as varchar) from device_status_log where device_sn = ?",
                        String.class, SN))
                .contains("ONLINE"), "状态流水必须留档原始 payload");
        verify(channel).basicAck(1L, false);
        verify(idempotent).tryAcquire("sim-status-1");

        // 同一状态重复上报：只刷新最近上报时间，不再写流水（避免高频上报把表撑爆）
        deliver(mnsBody("status", statusPayload("ONLINE", "2026-03-10 19:40:00")), "sim-status-2");
        assertEquals(1L, count("select count(*) from device_status_log where device_sn = ?", SN));
        assertEquals("2026-03-10 19:40:00", String.valueOf(jdbc.queryForObject(
                "select cast(last_report_time as varchar) from device_info where device_sn = ?", String.class, SN)));
        assertEquals("2026-03-10 19:32:25", String.valueOf(jdbc.queryForObject(
                "select cast(last_status_change_time as varchar) from device_info where device_sn = ?", String.class, SN)));

        // 状态真的变化：补一条流水
        deliver(mnsBody("status", statusPayload("OFFLINE", "2026-03-10 20:00:00")), "sim-status-3");
        assertEquals(2L, count("select count(*) from device_status_log where device_sn = ?", SN));
        assertEquals("OFFLINE", jdbc.queryForObject(
                "select online_status from device_info where device_sn = ?", String.class, SN));

        // 旧状态不得覆盖新状态：19:00 的 ONLINE 因 MQ 重试乱序晚到，必须被整条丢弃
        deliver(mnsBody("status", statusPayload("ONLINE", "2026-03-10 19:00:00")), "sim-status-stale");
        assertEquals("OFFLINE", jdbc.queryForObject(
                        "select online_status from device_info where device_sn = ?", String.class, SN),
                "过期状态报文不得覆盖当前状态");
        assertEquals(2L, count("select count(*) from device_status_log where device_sn = ?", SN),
                "被丢弃的过期状态不写流水");
        assertEquals("2026-03-10 20:00:00", String.valueOf(jdbc.queryForObject(
                "select cast(last_status_change_time as varchar) from device_info where device_sn = ?",
                String.class, SN)), "过期状态不得改动状态确立时间");
        assertEquals("2026-03-10 20:00:00", String.valueOf(jdbc.queryForObject(
                "select cast(last_report_time as varchar) from device_info where device_sn = ?",
                String.class, SN)), "过期状态不得改动最近上报时间");
    }

    @Test
    @DisplayName("冲泡链路：写入门店快照，并能被后台「设备管理 / 冲泡记录」两个页面读回")
    void brewMessageIsVisibleInAdminDeviceAndBrewPages() throws Exception {
        bindStore(storeA, "首次绑定");
        // 先来一条状态上报（设备上线），再用冲泡记录验证后台可见性
        deliver(mnsBody("status", statusPayload("ONLINE", "2026-03-10 21:00:00")), "sim-upload-pre");

        deliver(mnsBody("upload", uploadPayload("408", "2026-03-10 21:16:04")), "sim-upload-1");

        Map<String, Object> record = jdbc.queryForMap(
                "select device_sn, device_name, store_subject_id, store_name, tea_code, tea_name, "
                        + "customer_code, made_day, serial_number, cast(base_create_time as varchar) as base_create_time, "
                        + "cast(raw_payload as varchar) as raw_payload "
                        + "from device_brew_record where device_sn = ?", SN);
        assertEquals(DEVICE_ID, record.get("device_name"));
        assertEquals(storeA, ((Number) record.get("store_subject_id")).longValue());
        // 门店是「写入时刻」快照，设备之后换绑不影响历史
        assertEquals("星沙乐运魔方店", record.get("store_name"));
        assertEquals("209", record.get("tea_code"));
        assertEquals("欧米伽-3植物乳", record.get("tea_name"));
        assertEquals("90000001", record.get("customer_code"));
        assertEquals("20251021", record.get("made_day"));
        assertEquals("408", record.get("serial_number"));
        assertEquals("2026-03-10 21:16:04", String.valueOf(record.get("base_create_time")));
        // 原始 payload 留档，事后可追溯
        assertTrue(String.valueOf(record.get("raw_payload")).contains("ZPDS2512090086G"));

        // === 后台「设备管理」页：专用接口的分页查询 ===
        PageResult<AdminDeviceDTO> devicePage = deviceService.pageDevices(1, 10, SN, null, "bound", "ONLINE", 1);
        assertEquals(1L, devicePage.getTotal());
        AdminDeviceDTO dto = devicePage.getRecords().get(0);
        assertEquals(SN, dto.getDeviceSn());
        assertEquals(DEVICE_ID, dto.getDeviceName());
        assertEquals(Long.valueOf(storeA), dto.getStoreSubjectId());
        assertEquals("星沙乐运魔方店", dto.getStoreName());
        // 最近上报时间：status 与 upload 两类报文都算一次上报，取较新的那次
        assertEquals("2026-03-10 21:16:04", dto.getLastReportTime());

        // 未绑定 / 离线 / 停用筛选都不应该命中这台在线已绑定设备
        assertEquals(0L, deviceService.pageDevices(1, 10, null, null, "unbound", null, null).getTotal());
        assertEquals(0L, deviceService.pageDevices(1, 10, null, null, null, "OFFLINE", null).getTotal());
        assertEquals(0L, deviceService.pageDevices(1, 10, null, null, null, null, 0).getTotal());

        // === 后台「冲泡记录」页：通用 CRUD 只读资源 + 真实白名单 + 真实 SQL ===
        PageResult<Map<String, Object>> brewPage = crudService.page("deviceBrewRecords", 1, 10,
                Map.of("deviceSn", SN, "teaCode", "209"),
                Map.of("storeSubjectId", String.valueOf(storeA)),
                Map.of("ge_baseCreateTime", "2026-03-10 00:00:00", "lt_baseCreateTime", "2026-03-11 00:00:00"));
        assertEquals(1L, brewPage.getTotal());
        Map<String, Object> row = brewPage.getRecords().get(0);
        // 列名必须是驼峰（camelize），页面列 key 直接读这些字段
        assertEquals(SN, row.get("deviceSn"));
        assertEquals("欧米伽-3植物乳", row.get("teaName"));
        assertEquals("星沙乐运魔方店", row.get("storeName"));
        assertEquals("408", row.get("serialNumber"));

        // 页面时间范围是左闭右开：范围外不应命中
        assertEquals(0L, crudService.page("deviceBrewRecords", 1, 10, Map.of(),
                Map.of(), Map.of("ge_baseCreateTime", "2026-03-11 00:00:00")).getTotal());

        // 不在白名单内的搜索列必须被忽略（否则会变成任意列 like 注入面）
        assertEquals(1L, crudService.page("deviceBrewRecords", 1, 10,
                Map.of("rawPayload", "不存在的内容"), Map.of(), Map.of()).getTotal());

        // 状态流水资源同样可查（前面 ONLINE 那条上报已写一条流水）
        assertEquals(1L, crudService.page("deviceStatusLogs", 1, 10,
                Map.of("deviceSn", SN), Map.of(), Map.of()).getTotal());

        // 乱序保护：更旧的上报（MQ 重试/重复投递会乱序到达）不得把「最近上报」改小
        deliver(mnsBody("upload", uploadPayload("409", "2026-03-10 20:00:00")), "sim-upload-late");
        assertEquals("2026-03-10 21:16:04", jdbc.queryForObject(
                "select cast(last_report_time as varchar) from device_info where device_sn = ?",
                String.class, SN), "更旧的上报不得把最近上报时间改小");
    }

    @Test
    @DisplayName("幂等：MNS 重投同一 messageId、或唯一键相同，都不会产生第二行")
    void redeliveryAndDuplicateBusinessKeyAreIdempotent() throws Exception {
        String reportTime = "2026-03-10 21:16:04";

        // ① 同一 messageId 重投：MqIdempotent 占位拦下，业务不再执行
        when(idempotent.tryAcquire("sim-dup")).thenReturn(true, false);
        deliver(mnsBody("upload", uploadPayload("408", reportTime)), "sim-dup");
        deliver(mnsBody("upload", uploadPayload("408", reportTime)), "sim-dup");
        assertEquals(1L, count("select count(*) from device_brew_record where device_sn = ?", SN));
        verify(channel, times(2)).basicAck(1L, false);

        // ② 换一个 messageId、但业务唯一键相同（报文重复）：靠表唯一键兜底
        deliver(mnsBody("upload", uploadPayload("408", reportTime)), "sim-dup-other-id");
        assertEquals(1L, count("select count(*) from device_brew_record where device_sn = ?", SN),
                "唯一键 (device_sn, base_create_time, tea_code, serial_number) 必须真的拦住重复记录");
    }

    @Test
    @DisplayName("换绑：冲泡记录与状态流水保留写入时刻的门店快照，绑定历史完整可回溯")
    void rebindKeepsHistoricalStoreSnapshot() throws Exception {
        AdminUser.set(1L, "ops-tester");
        bindStore(storeA, "首次绑定");
        deliver(mnsBody("upload", uploadPayload("1", "2026-03-10 21:00:00")), "sim-rebind-1");

        bindStore(storeB, "由星沙乐运魔方店换绑");
        deliver(mnsBody("upload", uploadPayload("2", "2026-03-10 22:00:00")), "sim-rebind-2");

        // 老记录仍指向门店 A，新记录指向门店 B
        assertEquals("星沙乐运魔方店", jdbc.queryForObject(
                "select store_name from device_brew_record where serial_number = '1'", String.class));
        assertEquals("长沙国金中心店", jdbc.queryForObject(
                "select store_name from device_brew_record where serial_number = '2'", String.class));

        // 绑定历史：BIND(A) → UNBIND(A) → BIND(B)
        List<Map<String, Object>> history = deviceService.listBindingHistory(ensureDeviceId());
        assertEquals(3, history.size());
        assertEquals("BIND", history.get(2).get("action"));
        assertEquals("星沙乐运魔方店", history.get(2).get("storeName"));
        assertEquals("ops-tester", history.get(2).get("operator"));
        assertEquals("UNBIND", history.get(1).get("action"));
        assertEquals("BIND", history.get(0).get("action"));
        assertEquals("长沙国金中心店", history.get(0).get("storeName"));

        assertEquals(Long.valueOf(storeB), jdbc.queryForObject(
                "select store_subject_id from device_info where device_sn = ?", Long.class, SN));
    }

    @Test
    @DisplayName("边界：门店已删除仍可解绑、门店名超长被截断、坏报文留档不丢")
    void edgeCasesAreHandled() throws Exception {
        // ① 门店被逻辑删除后解绑：历史只留门店名快照，不能再因 store_subject_id NOT NULL 报 500（V77 修的就是这里）
        bindStore(storeA, "首次绑定");
        jdbc.update("update biz_subject set deleted = 1 where id = ?", storeA);
        assertDoesNotThrow(() -> deviceService.unbindStore(ensureDeviceId(), "门店已删除"));
        Map<String, Object> unbindRow = jdbc.queryForMap(
                "select action, store_subject_id, store_name from device_store_binding "
                        + "where action = 'UNBIND'");

        // 门店被删后：store_subject_id 置空（V77），但门店名快照必须保留，否则历史无法回溯；
        // 实现上不能走 BizSubjectMapper.selectById（@TableLogic 查不到已删门店）。
        assertNull(unbindRow.get("store_subject_id"));
        assertEquals("星沙乐运魔方店", unbindRow.get("store_name"));

        // ② 门店名超过 store_name 列宽（128）：截断落库，不能因 Data too long 回滚整条消费链路
        long storeC = jdbc.queryForObject("select id from biz_subject where code = 'S1'", Long.class);
        // 门店名取满 biz_subject.name 的列宽（128）：V75 的 store_name 只有 64，
        // 这种门店一旦绑定就会「Data too long」回滚整条消费链路，V77 才放宽到同宽。
        String longName = "长".repeat(128);
        jdbc.update("update biz_subject set name = ?, deleted = 0 where id = ?", longName, storeC);
        deviceService.bindStore(ensureDeviceId(), storeC, "超长门店名");
        deliver(mnsBody("upload", uploadPayload("9", "2026-03-10 23:00:00")), "sim-long-name");
        String stored = jdbc.queryForObject(
                "select store_name from device_brew_record where serial_number = '9'", String.class);
        assertEquals(128, stored.length(), "门店名快照必须能存满 128 列宽，不能被 64 拦成 Data too long");

        // ③ 坏报文：解析必须抛错，由 MnsPoller 留档后丢弃，不能猜、不能落业务表
        //    3a) payload 里没有 deviceSN（有合法时间）
        String badBody = mnsBody("status", "{\"baseCreateTime\":\"2026-03-10 10:00:00\",\"status\":\"ONLINE\"}");
        MnsParseException error = assertThrows(MnsParseException.class, () -> PARSER.parse(badBody, "bad-1"));
        assertTrue(error.getMessage().contains("deviceSN"), error.getMessage());
        //    3b) payload 里没有 baseCreateTime：不可以用当前时间兜底（否则重投会变成两条）
        MnsParseException timeError = assertThrows(MnsParseException.class,
                () -> PARSER.parse(mnsBody("status", "{\"deviceSN\":\"" + SN + "\",\"status\":\"ONLINE\"}"), "bad-2"));
        assertTrue(timeError.getMessage().contains("baseCreateTime"), timeError.getMessage());
        deviceService.recordMessageError("bad-1", "status", badBody, error.getMessage());

        assertEquals(1L, count("select count(*) from device_message_error where source_message_id = ?", "bad-1"));
        assertTrue(String.valueOf(jdbc.queryForObject(
                        "select cast(raw_body as varchar) from device_message_error where source_message_id = ?", String.class, "bad-1"))
                .startsWith("eyJ"), "坏报文必须原样留档（Base64 原文），便于对方改格式后回溯");
        // 坏报文只进留档表，不能产生任何业务记录（本用例里已有 1 条正常冲泡记录）
        assertEquals(1L, count("select count(*) from device_brew_record"));
    }

    @Test
    @DisplayName("只读资源：通用接口的新增/编辑/删除都必须被拒绝，事实数据不能被软删")
    void readOnlyResourcesRejectAllWrites() throws Exception {
        bindStore(storeA, "首次绑定");
        deliver(mnsBody("upload", uploadPayload("408", "2026-03-10 21:16:04")), "sim-readonly");
        long recordId = jdbc.queryForObject("select id from device_brew_record where device_sn = ?", Long.class, SN);

        // 冲泡记录 / 状态流水是设备上报的事实数据，后台只读：
        // writable 为空必须在写接口入口拦住，而不是靠「白名单过滤」——
        // 删除动作的 payload 是空的，白名单过滤对它是无效的（会直接 set deleted = 1）。
        assertThrows(BusinessException.class, () -> crudService.delete("deviceBrewRecords", recordId));
        assertThrows(BusinessException.class,
                () -> crudService.update("deviceBrewRecords", recordId, Map.of("teaName", "被改掉")));
        assertThrows(BusinessException.class,
                () -> crudService.create("deviceBrewRecords", Map.of("teaName", "凭空造")));
        assertThrows(BusinessException.class, () -> crudService.delete("deviceStatusLogs", 1L));

        assertEquals(0, jdbc.queryForObject(
                        "select deleted from device_brew_record where id = ?", Integer.class, recordId),
                "只读资源不能被软删，否则后台页面会当场查不到这条冲泡记录");
        assertEquals("欧米伽-3植物乳", jdbc.queryForObject(
                "select tea_name from device_brew_record where id = ?", String.class, recordId));
        assertEquals(1L, count("select count(*) from device_brew_record where device_sn = ?", SN));
    }

    @Test
    @DisplayName("删除台账：需先解绑；删除后同 SN 上报不再自动重建，恢复走「新增设备」并复用原行")
    void deletedDeviceIsNotResurrectedByReports() throws Exception {
        AdminUser.set(1L, "ops-tester");
        bindStore(storeA, "首次绑定");
        long deviceId = ensureDeviceId();

        // 还挂在门店上时不允许删除：正在服役的设备被误删后，消息就失去门店归属落点
        assertThrows(BusinessException.class, () -> deviceService.deleteDevice(deviceId, "误删测试"));

        deviceService.unbindStore(deviceId, "准备删除");
        deviceService.deleteDevice(deviceId, "换机，旧机下线");

        // 删除原因进库：audit_log 里可按 操作对象=deviceSN 查到原因、操作人与快照
        Map<String, Object> deleteAudit = jdbc.queryForMap(
                "select operator, module, action, target, cast(reason as varchar) as reason, "
                        + "cast(before_value as varchar) as before_value from audit_log "
                        + "where module = 'DEVICE' and action = 'DELETE'");
        assertEquals("ops-tester", deleteAudit.get("operator"));
        assertEquals("DELETE", deleteAudit.get("action"));
        assertEquals(SN, deleteAudit.get("target"));
        assertEquals("换机，旧机下线", deleteAudit.get("reason"));
        assertTrue(String.valueOf(deleteAudit.get("before_value")).contains("deviceName=" + DEVICE_ID),
                "审计旧值应包含台账快照");

        // 列表查不到（pageDevices 固定过滤 deleted = 0）
        assertEquals(0L, deviceService.pageDevices(1, 10, SN, null, null, null, null).getTotal());
        assertEquals(1, jdbc.queryForObject(
                "select deleted from device_info where id = ?", Integer.class, deviceId).intValue());

        // 设备还在上报：不重建台账，但冲泡事实数据仍落库（无门店归属）
        deliver(mnsBody("upload", uploadPayload("500", "2026-03-11 10:00:00")), "sim-after-delete");
        assertEquals(1, jdbc.queryForObject(
                        "select count(*) from device_info where device_sn = ?", Integer.class, SN).intValue(),
                "删除后不允许因为上报而自动重建台账（否则「删除」形同虚设）");
        assertEquals(1, jdbc.queryForObject(
                "select deleted from device_info where id = ?", Integer.class, deviceId).intValue());
        assertEquals(1L, count("select count(*) from device_brew_record where serial_number = '500'"),
                "事实数据不能因为台账被删就丢掉");
        assertNull(jdbc.queryForObject(
                "select store_subject_id from device_brew_record where serial_number = '500'", Long.class));

        // 状态流水是台账的附属数据：台账已删就不写（否则随上报无限增长且无人查看）
        deliver(mnsBody("status", statusPayload("ONLINE", "2026-03-11 10:05:00")), "sim-after-delete-status");
        assertEquals(0L, count("select count(*) from device_status_log where device_sn = ?", SN));

        // 运营要恢复：走「新增设备」，复用原行而不是插第二行同 SN 台账
        DeviceUpsert upsert = new DeviceUpsert();
        upsert.setDeviceSn(SN);
        upsert.setDeviceName(DEVICE_ID);
        upsert.setEnabled(1);
        deviceService.createDevice(upsert);
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from device_info where device_sn = ?", Integer.class, SN).intValue(),
                "恢复必须复用原行，不能留下两行同 SN 台账");
        assertEquals(0, jdbc.queryForObject(
                "select deleted from device_info where id = ?", Integer.class, deviceId).intValue());
        assertEquals(1L, deviceService.pageDevices(1, 10, SN, null, null, null, null).getTotal());

        // 恢复同样留痕，且不能覆盖掉上一次删除的原因（两条独立审计记录）
        Map<String, Object> restoreAudit = jdbc.queryForMap(
                "select operator, target, cast(reason as varchar) as reason from audit_log where action = 'RESTORE'");
        assertEquals("ops-tester", restoreAudit.get("operator"));
        assertEquals(SN, restoreAudit.get("target"));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from audit_log where target = ?", Integer.class, SN).intValue());
    }

    @Test
    @DisplayName("批量导入：整批严格校验，逻辑删除设备不占用设备ID")
    void batchImportIsStrictAndIgnoresDeletedIdentity() {
        long originalId = ensureDeviceId();

        DeviceImportResult duplicate = deviceService.importDevices(List.of(
                deviceRow("SN-NEW-1", DEVICE_ID),
                deviceRow("SN-NEW-2", "869978072404914")));

        assertFalse(duplicate.isAccepted());
        assertEquals(0, duplicate.getImported());
        assertTrue(duplicate.getErrors().stream().anyMatch(error -> error.contains("设备ID已存在")));
        assertEquals(1L, count("select count(*) from device_info"));

        deviceService.deleteDevice(originalId, "批量导入测试");
        DeviceImportResult accepted = deviceService.importDevices(List.of(
                deviceRow("SN-AFTER-DELETE", DEVICE_ID)));

        assertTrue(accepted.isAccepted());
        assertEquals(1, accepted.getImported());
        assertEquals(2L, count("select count(*) from device_info"));
        assertEquals(1L, count("select count(*) from device_info where device_sn = 'SN-AFTER-DELETE' and deleted = 0"));

        List<DeviceUpsert> atomicRows = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            atomicRows.add(deviceRow("SN-ATOMIC-" + i, "ID-ATOMIC-" + i));
        }
        atomicRows.add(deviceRow("SN' OR 1=1 --", "ID-BAD"));
        DeviceImportResult atomicRejected = deviceService.importDevices(atomicRows);
        assertFalse(atomicRejected.isAccepted());
        assertEquals(2L, count("select count(*) from device_info"), "存在非法行时整批不得写入");
    }

    // ==================== 链路工具 ====================

    /** 模拟 MnsPoller：对方 Base64 报文 → 解析 → 转投 MQ（JSON 消息体）→ 消费者 */
    private void deliver(String mnsBase64Body, String messageId) {
        DeviceMessageEnvelope envelope = PARSER.parse(mnsBase64Body, messageId);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(1L);
        properties.setMessageId(messageId);
        properties.setHeader(MqConstants.HEADER_MESSAGE_ID, messageId);
        properties.setHeader(MqConstants.HEADER_BIZ_KEY, envelope.getDeviceSn());
        // 与 RabbitConfig 的 Jackson2JsonMessageConverter 口径一致：MQ 上是信封对象的 JSON
        Message mqMessage = new Jackson2JsonMessageConverter().toMessage(envelope, properties);
        consumer.onDeviceMessage(mqMessage, channel);
    }

    private void bindStore(long storeSubjectId, String remark) {
        deviceService.bindStore(ensureDeviceId(), storeSubjectId, remark);
    }

    /**
     * 取设备台账 id；台账不存在时走「后台手工建档」接口创建。
     *
     * 生产上「先建档再绑定、之后才收到上报」和「先上报自动建档」两条路径都存在，
     * 这里显式覆盖前者。
     */
    private long ensureDeviceId() {
        List<Long> ids = jdbc.queryForList("select id from device_info where device_sn = ?", Long.class, SN);
        if (ids.isEmpty()) {
            DeviceUpsert upsert = new DeviceUpsert();
            upsert.setDeviceSn(SN);
            upsert.setDeviceName(DEVICE_ID);
            upsert.setAlias("三楼吧台机");
            upsert.setEnabled(1);
            deviceService.createDevice(upsert);
            ids = jdbc.queryForList("select id from device_info where device_sn = ?", Long.class, SN);
        }
        return ids.get(0);
    }

    private static DeviceUpsert deviceRow(String deviceSn, String deviceName) {
        DeviceUpsert upsert = new DeviceUpsert();
        upsert.setDeviceSn(deviceSn);
        upsert.setDeviceName(deviceName);
        upsert.setEnabled(1);
        return upsert;
    }

    /** 对方报文外层：Base64( { messagetype, payload: "<JSON 字符串>" } ) */
    private String mnsBody(String messagetype, String payloadJson) throws Exception {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("messagetype", messagetype);
        outer.put("payload", payloadJson);
        String json = MAPPER.writeValueAsString(outer);
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private String statusPayload(String status, String baseCreateTime) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deviceSN", SN);
        payload.put("deviceName", DEVICE_ID);
        payload.put("status", status);
        payload.put("baseCreateTime", baseCreateTime);
        return MAPPER.writeValueAsString(payload);
    }

    private String uploadPayload(String serialNumber, String baseCreateTime) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teaCode", 209);
        payload.put("teaName", "欧米伽-3植物乳");
        payload.put("customer", 90000001);
        payload.put("madeDay", "20251021");
        payload.put("serialNumber", serialNumber);
        payload.put("baseCreateTime", baseCreateTime);
        payload.put("deviceName", DEVICE_ID);
        payload.put("deviceSN", SN);
        return MAPPER.writeValueAsString(payload);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static void alter(Connection connection, String ddl) throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute(ddl);
        }
    }
}
