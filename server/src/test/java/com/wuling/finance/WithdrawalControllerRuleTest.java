package com.wuling.finance;

import com.wuling.common.audit.AuditLogService;
import com.wuling.finance.controller.WithdrawalController;
import com.wuling.finance.service.WithdrawalService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WithdrawalControllerRuleTest {

    @Test
    void publicRuleStatesThatEveryAmountRequiresOperationsReview() {
        WithdrawalController controller = new WithdrawalController(
                mock(WithdrawalService.class), mock(AuditLogService.class));

        Map<String, Object> rule = controller.rule().getData();

        assertEquals(0L, rule.get("instantLimit"));
        assertEquals(true, rule.get("auditRequired"));
        assertTrue(String.valueOf(rule.get("auditNote")).contains("所有提现"));
    }
}
