package com.wuling.common.exception;

import com.wuling.common.api.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    @Test
    void missingResourceReturnsHttp404() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.POST, "api/v1/app/comments");

        ResponseEntity<Result<Void>> response = handler.handleNotFound(exception);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        Result<Void> body = response.getBody();
        assertNotNull(body);
        assertEquals(404, body.getCode());
        assertEquals("资源不存在", body.getMessage());
    }

    /**
     * 字段超长必须给出可读提示，而不是「服务器内部错误」。
     *
     * <p>回归 2026-11-23 事故：商品保存时 2.2MB base64 写进
     * {@code VARCHAR(255)}，用户只看到「服务器内部错误」，
     * 完全无法定位是图片问题。
     */
    @Test
    void dataTooLongReturnsReadableHintWithColumnName() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        // 模拟 Spring 的异常包装链：DataIntegrityViolation -> MySQL 原始异常
        Exception mysqlRoot = new RuntimeException(
                "Data truncation: Data too long for column 'name' at row 1");
        org.springframework.dao.DataIntegrityViolationException exception =
                new org.springframework.dao.DataIntegrityViolationException(
                        "### Error updating database", mysqlRoot);

        Result<Void> body = handler.handleDataIntegrity(exception);

        assertNotNull(body);
        // 400 而非 500：这是入参不符合表结构，不是系统故障
        assertEquals(400, body.getCode());
        assertNotNull(body.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                body.getMessage().contains("name"),
                "提示应包含具体字段名，便于运营定位：" + body.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                body.getMessage().contains("过长"),
                "提示应说明是长度问题：" + body.getMessage());
    }

    /**
     * {@code image} 字段超长时给出针对性引导。
     *
     * <p>本案例中运营是在图片区域直接粘贴内容，通用提示不足以纠正行为。
     */
    @Test
    void imageTooLongSuggestsUsingUploadButton() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Exception mysqlRoot = new RuntimeException(
                "Data truncation: Data too long for column 'image' at row 1");
        org.springframework.dao.DataIntegrityViolationException exception =
                new org.springframework.dao.DataIntegrityViolationException("wrap", mysqlRoot);

        Result<Void> body = handler.handleDataIntegrity(exception);

        assertNotNull(body.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                body.getMessage().contains("上传"),
                "应引导使用上传按钮：" + body.getMessage());
    }

    @Test
    void duplicateEntryReturnsReadableHint() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Exception mysqlRoot = new RuntimeException(
                "Duplicate entry 'classic-001' for key 'uk_product_product_id'");
        org.springframework.dao.DataIntegrityViolationException exception =
                new org.springframework.dao.DataIntegrityViolationException("wrap", mysqlRoot);

        Result<Void> body = handler.handleDataIntegrity(exception);

        assertNotNull(body.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                body.getMessage().contains("已存在"),
                "唯一键冲突应提示已存在：" + body.getMessage());
    }

    @Test
    void nullColumnReturnsReadableHint() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Exception mysqlRoot = new RuntimeException(
                "Column 'name' cannot be null");
        org.springframework.dao.DataIntegrityViolationException exception =
                new org.springframework.dao.DataIntegrityViolationException("wrap", mysqlRoot);

        Result<Void> body = handler.handleDataIntegrity(exception);

        assertNotNull(body.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                body.getMessage().contains("不能为空"),
                "非空约束应提示必填：" + body.getMessage());
    }
}