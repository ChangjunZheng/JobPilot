package com.jobpilot.common;

/**
 * 请求追踪 ID 的共享常量（requestId 清理项）。
 * 常量放 common 而不是过滤器上：ApiResponse（common）与 RequestIdFilter（config）都要用它，
 * 依赖方向必须保持 config → common。
 */
public final class RequestId {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER = "X-Request-Id";

    private RequestId() {
    }
}
