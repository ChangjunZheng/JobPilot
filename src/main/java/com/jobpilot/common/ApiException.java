package com.jobpilot.common;

/**
 * 业务/接口层可预期异常：携带语义化错误码（{@link ErrorCode} 枚举，非裸字符串），
 * 由全局异常处理器转成统一失败响应。
 * <p>
 * code 用枚举而不是 String 的理由：处理器里要做「错误码 → HTTP 状态」的字符串比较，
 * 裸字符串拼错（少个下划线之类）不会报错，只会静默降级成另一种正常结果。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
