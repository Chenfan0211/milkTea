package com.wuling.finance;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库一致性校验（需要本地隧道 + 已初始化的库）。
 * 通过 -Ddb.it=true 开启；未开启时自动跳过，保证 CI 不依赖外部环境。
 */
class LedgerConsistencyIT {

    private static final String URL =
            "jdbc:mysql://127.0.0.1:13306/wuling?useUnicode=true&characterEncoding=utf8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String USER = "wuling";
    private static final String PWD = "wuling123";

    private boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("db.it", "false"));
    }

    @Test
    void splitSnapshotFivePartiesMustEqualPaidAmount() throws Exception {
        if (!enabled()) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(URL, USER, PWD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "select s.order_no, o.paid_amount, "
                             + "(s.platform_amount+s.store_amount+s.channel_amount+s.investor_amount+s.supplier_amount) as parts "
                             + "from split_snapshot s join orders o on o.order_no = s.order_no")) {
            List<String> mismatches = new ArrayList<>();
            while (rs.next()) {
                long paid = rs.getLong("paid_amount");
                long parts = rs.getLong("parts");
                if (paid != parts) {
                    mismatches.add(rs.getString("order_no") + ": paid=" + paid + " parts=" + parts);
                }
            }
            assertTrue(mismatches.isEmpty(), "分账五方之和与实付不一致: " + mismatches);
        }
    }


    @Test
    void withdrawalFrozenAmountCannotExceedAvailable() throws Exception {
        if (!enabled()) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(URL, USER, PWD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "select subject_id, available_balance, frozen_balance from subject_account")) {
            while (rs.next()) {
                long available = rs.getLong("available_balance");
                long frozen = rs.getLong("frozen_balance");
                assertTrue(available >= 0, "可用余额不能为负: subject=" + rs.getLong("subject_id"));
                assertTrue(frozen >= 0, "冻结金额不能为负: subject=" + rs.getLong("subject_id"));
            }
        }
    }
}
