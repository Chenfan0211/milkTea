package com.wuling.common.logging;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * traceId 透传拦截器（服务间内部 RestClient 调用）。
 *
 * <p>目的：当服务 A 通过 RestClient 调用服务 B 的 {@code /internal/**} 接口时，
 * 把当前线程 MDC 中的 traceId 写入 {@code X-Trace-Id} 请求头，使服务 B 的
 * {@link RequestLogFilter} 能复用同一个 traceId，跨服务调用链可被串联排查。
 *
 * <p>为什么从 MDC 读而不是从请求上下文读：内部调用发生在 Servlet 线程内，
 * {@link RequestLogFilter} 已在入口把 traceId 放进 MDC（key="traceId"），
 * 此处直接读取即可；取不到（如非请求线程的定时任务主动调内部接口）则跳过，
 * 由下游自生成，不影响功能。
 *
 * <p>安全性：{@code X-Trace-Id} 仅用于日志关联，不含敏感信息；
 * 下游 {@link RequestLogFilter} 只是读取该头用于打印，不参与任何鉴权/业务逻辑。
 */
public class TraceIdPropagationInterceptor implements ClientHttpRequestInterceptor {

    /** MDC key，与 RequestLogFilter 写入保持一致。 */
    static final String MDC_TRACE_ID = "traceId";

    /** 透传头，与 RequestLogFilter / GatewayLogFilter 保持一致。 */
    static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String traceId = MDC.get(MDC_TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().set(HEADER_TRACE_ID, traceId);
        }
        return execution.execute(request, body);
    }
}
