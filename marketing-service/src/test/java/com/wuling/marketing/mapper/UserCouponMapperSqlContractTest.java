package com.wuling.marketing.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户券 Mapper 的 SQL 契约守卫（源码级）。
 *
 * <p>为什么用源码断言：这些约束写在 SQL 注解里，单测把 mapper mock 掉就覆盖不到；
 * 一旦有人删掉关键子句（例如退款回退忘记清 use_time），
 * 只能在生产上被巡检项「非已使用却带 use_time」事后发现。
 * 这里把「必须存在的子句」变成提交前就会失败的用例。
 */
class UserCouponMapperSqlContractTest {

    @Test
    @DisplayName("退款回退必须同时清空 use_time（否则「非已使用却带核销时间」巡检必然告警）")
    void restoreAfterRefundMustClearUseTime() throws Exception {
        Path mapper = Paths.get("src/main/java/com/wuling/marketing/mapper/UserCouponMapper.java");
        assertTrue(Files.exists(mapper), "找不到 UserCouponMapper.java（测试工作目录应为 marketing-service 模块）");
        String source = Files.readString(mapper, StandardCharsets.UTF_8);

        int methodIdx = source.indexOf("int restoreAfterRefund(");
        assertTrue(methodIdx > 0, "restoreAfterRefund 必须存在");

        int updateIdx = source.lastIndexOf("@Update(", methodIdx);
        assertTrue(updateIdx > 0, "restoreAfterRefund 必须带 @Update 注解");
        String snippet = source.substring(updateIdx, methodIdx);

        assertTrue(snippet.contains("use_time = null"),
                "退款回退必须把 use_time 置空：券回到 UNUSED/EXPIRED 后，use_time 不再有意义");
        assertTrue(snippet.contains("status in ('USED','LOCKED')"),
                "退款回退只能作用于 USED/LOCKED 的券");
    }
}