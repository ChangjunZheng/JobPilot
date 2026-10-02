package com.jobpilot.common;

import com.jobpilot.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一失败路径：可预期异常转 ApiResponse.fail，不向外泄漏堆栈。
 * （ARCHITECTURE.md §6：工具/服务异常不直接穿透 Controller）
 * <p>
 * 401 与 404 额外写 {@code SECURITY} 前缀的安全日志（I-1b）：401 是认证被拒；
 * 404 里混着「跨租户探测」——租户拦截器把别人的资源过滤成不存在，无法区分
 * 「猜错 ID」与「越权探测」，那就都记下来供检索。只记 method/uri/当前租户，不记请求体。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UnauthorizedException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleUnauthorized(UnauthorizedException e, HttpServletRequest request) {
        log.warn("SECURITY 401 method={} uri={} user={} reason={}",
                request.getMethod(), request.getRequestURI(), UserContext.get(), e.getMessage());
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException e, HttpServletRequest request) {
        HttpStatus status = "NOT_FOUND".equals(e.getCode())
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        if (status == HttpStatus.NOT_FOUND) {
            log.warn("SECURITY 404 method={} uri={} user={}",
                    request.getMethod(), request.getRequestURI(), UserContext.get());
        }
        return ResponseEntity.status(status).body(ApiResponse.fail(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleInvalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        return ApiResponse.fail("BAD_REQUEST", message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResponse.fail("BAD_REQUEST", e.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail("METHOD_NOT_ALLOWED", "不支持的请求方法"));
    }

    // 兜底，处理所有未预期的异常
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ApiResponse.fail("INTERNAL_ERROR", "服务暂时不可用，请稍后重试");
    }
}
