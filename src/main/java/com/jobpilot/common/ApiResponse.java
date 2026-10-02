package com.jobpilot.common;

import org.slf4j.MDC;

/**
 * API 响应类
 * ApiResponse
 * @param success
 * @param data
 * @param error
 * @param requestId 本次请求的追踪 ID（由 RequestIdFilter 写入 MDC；用户报障可凭它与服务端日志对账）
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorBody error,
        String requestId
) {

    public record ErrorBody(String code, String message) {
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, MDC.get(RequestId.MDC_KEY));
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, null, new ErrorBody(code, message), MDC.get(RequestId.MDC_KEY));
    }
}
