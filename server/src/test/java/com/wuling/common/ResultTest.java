package com.wuling.common;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResultTest {

    @Test
    void okShouldSetSuccessCode() {
        Result<String> result = Result.ok("data");
        assertEquals(ResultCode.SUCCESS, result.getCode());
        assertEquals("ok", result.getMessage());
        assertEquals("data", result.getData());
    }

    @Test
    void failShouldSetCodeAndMessage() {
        Result<Void> result = Result.fail(500, "boom");
        assertEquals(500, result.getCode());
        assertEquals("boom", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void pageResultShouldKeepRecords() {
        PageResult<String> page = PageResult.of(List.of("a", "b"), 1, 10, 2);
        assertEquals(2, page.getRecords().size());
        assertEquals(1, page.getCurrent());
        assertEquals(10, page.getSize());
        assertEquals(2, page.getTotal());
    }
}
