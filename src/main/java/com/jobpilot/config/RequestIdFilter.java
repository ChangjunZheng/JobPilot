package com.jobpilot.config;

import com.jobpilot.common.RequestId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 为每个请求生成追踪 ID（I-1 顺带清理项）：写入 MDC 供 {@code ApiResponse.requestId} 与日志使用，
 * 并以 {@code X-Request-Id} 响应头返回给客户端。用户报障时凭报文里的 requestId 即可在服务端日志对账。
 * <p>
 * 用 Servlet Filter 而不是拦截器：requestId 要覆盖错误转发（/error）与 401 等所有路径，
 * 且必须在任何业务代码读 MDC 之前就位、在响应完成后清理。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put(RequestId.MDC_KEY, requestId);
        response.setHeader(RequestId.HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestId.MDC_KEY); // 线程池复用：不清会把上一个请求的 ID 泄给下一个
        }
    }
}
