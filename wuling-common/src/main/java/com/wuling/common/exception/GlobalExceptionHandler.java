package com.wuling.common.exception;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return Result.fail(ResultCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBind(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return Result.fail(ResultCode.BAD_REQUEST, message);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(ResultCode.NOT_FOUND, "资源不存在"));
    }

    /**
     * 数据库约束冲突（字段超长 / 必填为空 / 唯一键重复）。
     *
     * <p><b>为什么要单独处理</b>：2026-11-23 事故——商品保存时把 2.2MB 的
     * base64 图片写进 {@code VARCHAR(255)} 的 {@code image} 列，
     * MySQL 抛 {@code Data too long for column 'image'}。
     * 当时落到下面的兜底分支，前端只看到「服务器内部错误」，
     * 运营完全无法定位（提示与实际原因毫无关联）。
     *
     * <p>这类异常本质是「入参不符合表结构」，属客户端问题（400），
     * 不是系统故障（500），因此降级到 WARN 并按语义回可读提示。
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public Result<Void> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException e) {
        String detail = rootMessage(e);
        String hint = toReadableHint(detail);
        log.warn("数据库约束冲突：{}（原始：{}）", hint, detail);
        return Result.fail(ResultCode.BAD_REQUEST, hint);
    }

    /**
     * 把底层数据库错误翻译成运营能看懂的话。
     *
     * <p>只做有限的模式匹配，匹配不到就给通用提示——
     * 宁可提示笼统，也不要把 SQL 细节暴露给前端。
     */
    private String toReadableHint(String detail) {
        if (detail == null) {
            return "数据格式不符合要求，请检查后重试";
        }
        String lower = detail.toLowerCase();
        if (lower.contains("data too long")) {
            String column = extractQuoted(detail, "column '");
            if ("image".equalsIgnoreCase(column)) {
                // 本案例专用提示：引导去用上传按钮，而不是继续粘贴内容
                return "图片内容过长，请使用「上传」按钮选择图片，不要直接粘贴图片内容";
            }
            return column == null
                    ? "字段内容过长，请精简后重试"
                    : "字段「" + column + "」内容过长，请精简后重试";
        }
        if (lower.contains("cannot be null") || lower.contains("doesn't have a default value")) {
            String column = extractQuoted(detail, "Column '");
            return column == null
                    ? "存在必填字段未填写，请检查后重试"
                    : "字段「" + column + "」不能为空";
        }
        if (lower.contains("duplicate entry")) {
            return "该记录已存在，请勿重复提交";
        }
        if (lower.contains("out of range") || lower.contains("incorrect integer value")) {
            return "数值超出允许范围，请检查后重试";
        }
        return "数据格式不符合要求，请检查后重试";
    }

    /** 取最内层异常消息（Spring 会把 MySQL 异常层层包装）。 */
    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? e.getMessage() : msg;
    }

    /** 从 {@code column 'xxx'} 这类文本里取出引号内内容。 */
    private String extractQuoted(String text, String marker) {
        int idx = text.toLowerCase().indexOf(marker.toLowerCase());
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        int end = text.indexOf('\'', start);
        return end > start ? text.substring(start, end) : null;
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("unexpected error", e);
        return Result.fail(ResultCode.ERROR, "服务器内部错误");
    }
}