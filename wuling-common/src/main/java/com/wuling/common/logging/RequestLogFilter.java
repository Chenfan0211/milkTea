package com.wuling.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 统一请求日志过滤器：记录每次调用的方法、路径、参数、调用人、耗时与响应时长。
 *
 * <p>目的：线上排查时能定位「谁、在什么时候、调了什么接口、传了什么参数、返回什么」。
 *
 * <p>安全约束（重要）：
 * <ul>
 *   <li>参数<b>默认脱敏</b>：token / 手机号 / 签名 / encryptedData / iv / 验证码
 *       会被替换为 ***，避免凭据与隐私落盘；</li>
 *   <li>仅当 {@code app.log.request.full=true}（环境变量 LOG_REQUEST_FULL）时
 *       才原样输出，用于临时排查，<b>排查完必须关闭</b>；</li>
 *   <li>body 超过 {@code app.log.request.max-body}（默认 2000 字符）截断。</li>
 * </ul>
 *
 * <p>性能：使用 Spring 的 ContentCaching 包装器，只缓存不消费，不影响业务读取 body；
 * 响应体过大时同样截断。日志级别为 INFO，生产默认开启。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
// 仅在 Servlet 栈生效：gateway 是 WebFlux(Netty)，引入 Servlet Filter 会导致启动失败
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("REQUEST");

    /** 是否原样打印全部参数（默认 false = 脱敏）。 */
    private final boolean fullLog;
    /** 单字段最大长度。 */
    private final int maxBody;

    public RequestLogFilter(@Value("${app.log.request.full:false}") boolean fullLog,
                            @Value("${app.log.request.max-body:2000}") int maxBody) {
        this.fullLog = fullLog;
        this.maxBody = maxBody;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 探活与静态资源不打日志，避免噪声淹没业务调用
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator") || uri.startsWith("/favicon");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 已在包装过的请求（如嵌套 filter）上不再重复包装
        if (request instanceof ContentCachingRequestWrapper) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("traceId", traceId);
        long start = System.currentTimeMillis();

        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } finally {
            long cost = System.currentTimeMillis() - start;
            try {
                logRequest(requestWrapper, responseWrapper, traceId, cost);
            } catch (Exception e) {
                // 日志失败绝不能影响业务响应
                log.warn("[{}] 请求日志记录失败: {}", traceId, e.getMessage());
            }
            MDC.remove("traceId");
            // 必须回写响应体，否则客户端拿不到内容
            responseWrapper.copyBodyToResponse();
        }
    }

    private void logRequest(ContentCachingRequestWrapper request,
                            ContentCachingResponseWrapper response,
                            String traceId,
                            long cost) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        String query = request.getQueryString();
        int status = response.getStatus();

        // 调用人：从已认证上下文或网关注入的用户标识读取（不存在则为匿名）
        String userId = firstNonBlank(request.getHeader("X-User-Id"), "anonymous");

        String requestBody = readBody(request.getContentAsByteArray());
        String responseBody = readBody(response.getContentAsByteArray());

        StringBuilder sb = new StringBuilder(256);
        sb.append("trace=").append(traceId)
                .append(" user=").append(userId)
                .append(" ").append(method).append(" ").append(uri);
        if (query != null && !query.isEmpty()) {
            sb.append("?").append(mask(query));
        }
        sb.append(" status=").append(status)
                .append(" cost=").append(cost).append("ms");

        if (!requestBody.isEmpty()) {
            sb.append(" req=").append(mask(requestBody));
        }
        // 仅错误响应记录响应体，避免正常业务日志膨胀
        if (status >= 400 && !responseBody.isEmpty()) {
            sb.append(" resp=").append(mask(responseBody));
        }

        if (status >= 500) {
            log.error(sb.toString());
        } else if (status >= 400) {
            log.warn(sb.toString());
        } else {
            log.info(sb.toString());
        }
    }

    private String readBody(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return SensitiveDataMasker.truncate(new String(bytes, StandardCharsets.UTF_8), maxBody);
    }

    /** 未开启 full 时做脱敏。 */
    private String mask(String text) {
        return fullLog ? text : SensitiveDataMasker.maskJson(text);
    }

    private String firstNonBlank(String a, String fallback) {
        return (a == null || a.isBlank()) ? fallback : a;
    }
}
