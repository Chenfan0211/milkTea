package com.wuling.subject.controller;

import com.wuling.subject.service.LeaderAttributionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminLeaderCodeTest {

    private JdbcTemplate jdbc;
    private AdminLeaderController controller;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:leader-code-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", ""
        ));
        jdbc.execute("create table app_user ("
                + "id bigint primary key,"
                + "nick_name varchar(64),"
                + "phone varchar(32),"
                + "status int not null default 1,"
                + "deleted tinyint not null default 0)");
        jdbc.execute("create table biz_subject ("
                + "id bigint not null auto_increment,"
                + "code varchar(64) not null,"
                + "name varchar(128) not null,"
                + "subject_type varchar(32) not null,"
                + "status varchar(32),"
                + "bound_user_id bigint,"
                + "deleted tinyint not null default 0,"
                + "primary key (id),"
                + "unique key uk_biz_subject_code (code))");
        jdbc.execute("create table user_role_grant ("
                + "id bigint not null auto_increment,"
                + "user_id bigint not null,"
                + "role_code varchar(32) not null,"
                + "subject_id bigint not null,"
                + "status varchar(32),"
                + "grant_time timestamp,"
                + "deleted tinyint not null default 0,"
                + "primary key (id))");
        jdbc.update("insert into app_user (id, nick_name, phone) values (7, '团长用户', '13800000000')");
        controller = new AdminLeaderController(jdbc, new LeaderAttributionService(jdbc));
    }

    @Test
    @DisplayName("新建团长主体使用前缀、8 位随机数和自增主键")
    void createLeaderUsesRandomCodeRule() {
        Map<String, Object> created = controller.createLeader(Map.of("userId", 7, "name", "团长甲")).getData();
        String id = String.valueOf(created.get("id"));
        String code = String.valueOf(created.get("code"));

        assertTrue(
                code.matches("LD-\\d{8}" + Pattern.quote(id)),
                "团长编码应符合 LD-8位随机数" + id + "，实际：" + code
        );
    }

    @Test
    @DisplayName("团长停用后重新启用复用原主体和原编码")
    void recreatingLeaderReusesExistingSubjectAndCode() {
        Map<String, Object> created = controller.createLeader(Map.of("userId", 7, "name", "团长甲")).getData();
        long subjectId = ((Number) created.get("id")).longValue();
        String code = String.valueOf(created.get("code"));

        controller.removeLeader(subjectId);
        Map<String, Object> restored = controller.createLeader(Map.of("userId", 7, "name", "团长甲")).getData();

        assertEquals(subjectId, ((Number) restored.get("id")).longValue());
        assertEquals(code, restored.get("code"));
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from user_role_grant where user_id = 7 and status = 'active' and deleted = 0",
                Long.class
        ));
    }
}
