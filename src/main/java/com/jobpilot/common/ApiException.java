package com.jobpilot.common;

/** 
 * 业务/接口层可预期异常：携带语义化错误码，由全局异常处理器转成统一失败响应
 * 
 */
public class ApiException extends RuntimeException {

    private final String code;

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
