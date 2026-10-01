package com.jobpilot.common;

/**
 * 未认证 / 缺少身份上下文。
 * <p>
 * 单独成类而不是复用 {@link ApiException} 的 code，是因为
 * {@code GlobalExceptionHandler} 把所有 {@code ApiException} 固定映射成 HTTP 400——
 * 鉴权失败必须是 401，否则客户端无法区分「参数错误」与「需要重新登录」。
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super("UNAUTHENTICATED", message);
    }
}
