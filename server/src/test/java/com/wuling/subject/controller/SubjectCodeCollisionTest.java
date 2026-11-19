package com.wuling.subject.controller;

import com.wuling.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主体编码撞号回归（真 SQL，H2 MySQL 兼容模式）。
 *
 * <p><b>为什么必须跑真库</b>：本次故障的根因是「计数口径 ≠ 唯一键口径」——
 * {@code uk_biz_subject_code} 不含 {@code deleted}，而生成器按非删除行数推算序号。
 * 这种错只有让唯一键真正生效才暴露得出来，mock 掉 JdbcTemplate 会得到假绿。
 *
 * <p>三个覆盖场景（对应线上三种复现路径）：
 * <ol>
 *   <li><b>软删除后再新增</b>：删除 ST-1003 后，旧算法会再发一次 ST-1005 → 撞库；</li>
 *   <li><b>手工编码已占用</b>：导入模板里的 RS-1004 已被用过 → 必须给出可读提示，
 *       而不是 DuplicateKeyException 冒到全局兜底变成「服务器内部错误」；</li>
 *   <li><b>连续新增</b>：每次编码都不同，且不残留 {@code -TMP-} 占位码。</li>
 * </ol>
 */
class SubjectCodeCollisionTest {

    private JdbcTemplate jdbcTemplate;
    private AdminSubjectProfileController controller;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:subject-code-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);

        // 按 V1__schema_core.sql 的真实约束建表：code 上必须有全局唯一键，
        // 且唯一键不包含 deleted（这正是「软删除仍占号」的来源）。
        jdbcTemplate.execute("create table biz_subject ("
                + "id bigint not null auto_increment,"
                + "code varchar(64) not null,"
                + "name varchar(128) not null,"
                + "subject_type varchar(32) not null,"
                + "status varchar(32) null default 'active',"
                + "bound_user_id bigint null,"
                + "create_time timestamp not null default current_timestamp,"
                + "update_time timestamp not null default current_timestamp,"
                + "deleted tinyint not null default 0,"
                + "primary key (id),"
                + "unique key uk_biz_subject_code (code))");
        jdbcTemplate.execute("create table channel_profile ("
                + "id bigint not null auto_increment,"
                + "subject_id bigint not null,"
                + "location varchar(64) null,"
                + "store_type varchar(64) null,"
                + "create_time timestamp not null default current_timestamp,"
                + "update_time timestamp not null default current_timestamp,"
                + "deleted tinyint not null default 0,"
                + "primary key (id))");
        jdbcTemplate.execute("create table store_profile ("
                + "id bigint not null auto_increment,"
                + "subject_id bigint not null,"
                + "deleted tinyint not null default 0,"
                + "primary key (id))");
        // 渠道列表会连带统计已绑定门店数（boundStoreCount），缺表会让断言前的查询就失败。
        jdbcTemplate.execute("create table channel_store ("
                + "id bigint not null auto_increment,"
                + "channel_subject_id bigint not null,"
                + "store_subject_id bigint not null,"
                + "create_time timestamp not null default current_timestamp,"
                + "update_time timestamp not null default current_timestamp,"
                + "deleted tinyint not null default 0,"
                + "primary key (id))");
        jdbcTemplate.execute("create table investor_profile ("
                + "id bigint not null auto_increment,"
                + "subject_id bigint not null,"
                + "investable_store_count int null default 0,"
                + "sign_status varchar(32) null,"
                + "deleted tinyint not null default 0,"
                + "primary key (id))");
        jdbcTemplate.execute("create table supplier_profile ("
                + "id bigint not null auto_increment,"
                + "subject_id bigint not null,"
                + "product_count int null default 0,"
                + "contact_name varchar(64) null,"
                + "phone varchar(32) null,"
                + "address varchar(255) null,"
                + "email varchar(128) null,"
                + "remark varchar(255) null,"
                + "status varchar(32) null,"
                + "deleted tinyint not null default 0,"
                + "primary key (id))");

        // 与线上种子一致：5 家门店 ST-1001..1005、3 个渠道 RS-1000..1002。
        for (int i = 1; i <= 5; i++) {
            insertSeed("ST-100" + i, "门店" + i, "STORE");
        }
        for (int i = 0; i <= 2; i++) {
            insertSeed("RS-100" + i, "资源方" + i, "CHANNEL");
        }

        controller = new AdminSubjectProfileController(jdbcTemplate);
    }

    @Test
    @DisplayName("软删除一家门店后再新增：不再撞唯一键（旧算法在此必炸）")
    void creatingChannelAfterSoftDeleteDoesNotCollide() {
        // 先删掉一条渠道，制造「非删除行数回退、但编码仍被占用」的状态。
        jdbcTemplate.update("update biz_subject set deleted = 1 where code = 'RS-1001'");

        Map<String, Object> payload = channelPayload("新资源方");
        controller.createChannel(payload).getData();

        // 旧算法：非删除渠道数 = 2 → RS-1002，撞已存在的 RS-1002。
        long surviving = jdbcTemplate.queryForObject(
                "select count(*) from biz_subject where code = 'RS-1002'", Long.class);
        assertEquals(1L, surviving, "RS-1002 不应被重复插入");
    }

    @Test
    @DisplayName("连续新增多个渠道：编码各不相同、无占位码残留")
    void consecutiveCreatesYieldDistinctCodes() {
        Map<String, Object> firstCreated = controller.createChannel(channelPayload("甲")).getData();
        Map<String, Object> secondCreated = controller.createChannel(channelPayload("乙")).getData();
        String first = String.valueOf(firstCreated.get("code"));
        String second = String.valueOf(secondCreated.get("code"));

        assertNotEquals(first, second, "两次新增拿到了同一个编码");
        assertGeneratedCode("RS", firstCreated.get("id"), first);
        assertGeneratedCode("RS", secondCreated.get("id"), second);

        Long placeholders = jdbcTemplate.queryForObject(
                "select count(*) from biz_subject where code like '%-TMP-%'", Long.class);
        assertEquals(0L, placeholders, "占位码必须已回写为正式编码");
    }

    @Test
    @DisplayName("手工填了被占用的编码：给出可读提示，而不是 500")
    void manualCodeAlreadyTakenGivesReadableError() {
        Map<String, Object> payload = channelPayload("撞号资源方");
        payload.put("code", "RS-1000");

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.createChannel(payload));
        assertTrue(error.getMessage().contains("RS-1000"),
                "提示里要带上冲突的编码，否则运营无从定位：" + error.getMessage());
    }

    @Test
    @DisplayName("手工编码占用的是已逻辑删除的行：同样要拦截（唯一键不区分 deleted）")
    void manualCodeTakenByDeletedRowIsRejected() {
        jdbcTemplate.update("update biz_subject set deleted = 1 where code = 'RS-1000'");

        Map<String, Object> payload = channelPayload("复用已删编码");
        payload.put("code", "RS-1000");

        // 若只查 deleted = 0，这里就会放过，随后 DuplicateKeyException 变成「服务器内部错误」。
        assertThrows(BusinessException.class, () -> controller.createChannel(payload));
    }

    @Test
    @DisplayName("手工编码未占用时按原值写入，不做改写")
    void manualFreeCodeIsRespected() {
        Map<String, Object> payload = channelPayload("自定义编码");
        payload.put("code", "SU-TEST37");
        Map<String, Object> created = controller.createChannel(payload).getData();

        assertEquals("SU-TEST37", created.get("code"));
    }

    @Test
    @DisplayName("编码留空即自动生成，不需要前端传")
    void blankCodeIsGeneratedServerSide() {
        Map<String, Object> payload = channelPayload("留空编码");
        payload.put("code", "   ");
        Map<String, Object> created = controller.createChannel(payload).getData();

        assertGeneratedCode("RS", created.get("id"), String.valueOf(created.get("code")));
    }

    private void insertSeed(String code, String name, String type) {
        jdbcTemplate.update("insert into biz_subject (code, name, subject_type, status) values (?, ?, ?, 'active')",
                code, name, type);
    }

    private static Map<String, Object> channelPayload(String name) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        // 渠道的表单必填项（与前端一致）：所在地、门店类型
        payload.put("location", "长沙市");
        payload.put("storeType", "奶茶/饮品");
        payload.put("status", "active");
        return payload;
    }

    private static void assertGeneratedCode(String prefix, Object idValue, String code) {
        String id = String.valueOf(idValue);
        assertTrue(
                code.matches(Pattern.quote(prefix) + "-\\d{8}" + Pattern.quote(id)),
                "编码应为 " + prefix + "-8位随机数" + id + "，实际：" + code
        );
    }

    /** 防御：确认 H2 真的按 MySQL 口径建出了唯一键，否则本测试全是假绿。 */
    @Test
    @DisplayName("自检：唯一键确实生效（否则上面的断言毫无意义）")
    void uniqueKeyIsActuallyEnforced() {
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> insertSeed("ST-1001", "重复门店", "STORE"));

        List<String> indexes = jdbcTemplate.queryForList(
                "select index_name from information_schema.indexes where table_name = 'biz_subject'",
                String.class);
        assertTrue(indexes.stream().anyMatch(i -> i.toLowerCase().contains("uk_biz_subject_code")),
                "biz_subject 缺少 code 唯一键，测试环境与线上不一致：" + indexes);
    }

    /**
     * 反向对照：用<b>旧算法</b>复算一次，证明它确实会算出已被占用的编码。
     *
     * <p>保留这条用例的目的：把「根因」变成可执行证据，而不是注释里的一句话。
     * 旧公式 {@code 前缀 + (非删除行数 + 1000)} 在「软删除一条」之后：
     * 3 个渠道删 1 个 → 非删除行数 2 → 算出 RS-1002，而 RS-1002 仍存在。
     */
    @Test
    @DisplayName("反向对照：旧公式（按非删除行数推算）确实会算出重复编码")
    void legacyFormulaWouldHaveCollided() {
        jdbcTemplate.update("update biz_subject set deleted = 1 where code = 'RS-1001'");

        // 旧实现：select count(*) from biz_subject where subject_type = 'CHANNEL'
        Long legacCount = jdbcTemplate.queryForObject(
                "select count(*) from biz_subject where subject_type = 'CHANNEL' and deleted = 0", Long.class);
        String legacyCode = "RS-" + String.format("%04d", (legacCount == null ? 0 : legacCount) + 1000);

        assertEquals("RS-1002", legacyCode, "旧公式此时算出的就是已存在的 RS-1002");
        assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> insertSeed(legacyCode, "旧算法产物", "CHANNEL"),
                "旧算法算出的编码应被唯一键拦下 —— 这正是线上 500 的来源");

        // 新实现同一场景下拿到的是主键派生码，与存量完全无关
        String newCode = String.valueOf(controller.createChannel(channelPayload("新算法产物")).getData().get("code"));
        assertNotEquals(legacyCode, newCode);
    }
}
