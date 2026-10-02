package com.jobpilot.common;

/**
 * 业务错误码的封闭集合（I-1b 后收拢）：{@link ApiException} 只接受这里的常量，
 * 杜绝 {@code new ApiException("NOTFOUND", ...)} 这类拼写漂移——裸字符串拼错时
 * 字符串比不中，异常会被静默吸收成另一种正常结果（如 404 降级成 400），没有任何报错。
 * <p>
 * 线上契约：响应信封里的 {@code error.code} 即枚举 {@code name()}，与历史字符串完全一致，
 * 客户端不受影响。新增常量时，{@code GlobalExceptionHandler} 里映射 HTTP 状态的
 * switch 表达式<b>没有 default 分支</b>——漏写映射直接编译失败，收拢的意义就在这里。
 */
public enum ErrorCode {
    /** 通用请求错误（参数校验、取值非法） */
    BAD_REQUEST,
    /** 目标资源不存在或不属于当前租户（两者对外不可区分，见租户隔离设计） */
    NOT_FOUND,
    /** 注册邮箱已被占用 */
    EMAIL_TAKEN,
    /** 邮箱或密码不正确（刻意不区分两种失败，防探测） */
    BAD_CREDENTIALS,
    /** 账号被禁用 */
    ACCOUNT_DISABLED,
    /** 注册未勾选隐私政策同意 */
    PRIVACY_CONSENT_REQUIRED,
    /** 未认证 / 令牌无效或已注销；由 {@code UnauthorizedException} 专用，固定映射 401 */
    UNAUTHENTICATED
}
