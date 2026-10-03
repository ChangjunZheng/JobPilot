package com.jobpilot.ai;

/**
 * 工具面错误码的封闭集合（ARCHITECTURE.md §6）。
 * <p>
 * <b>与 {@code com.jobpilot.common.ErrorCode} 分开是有意的</b>：后者绑定 HTTP 状态映射，
 * 且 {@code GlobalExceptionHandler} 用不带 default 的 switch 穷尽映射——把「工具内部失败」
 * 塞进去会给那个 switch 塞进一堆永远走不到的分支。工具失败是给模型看的，不是给 HTTP 客户端看的。
 * <p>
 * 这些值会作为 {@code ToolExecutionResult.errorCode()} 出现在 trace 里，因此也承担
 * 「失败归因」的作用（PRD-FP-3.1）。
 */
public final class ToolErrorCode {

    /** 工具执行抛出异常（未归类） */
    public static final String TOOL_FAILED = "TOOL_FAILED";
    /** 工具执行超时 */
    public static final String TIMEOUT = "TIMEOUT";
    /** 参数非法：JSON 解析失败、缺必填字段、类型不对 */
    public static final String INVALID_ARGUMENTS = "INVALID_ARGUMENTS";
    /** 依赖不可用（如向量库离线且降级也失败） */
    public static final String UPSTREAM_UNAVAILABLE = "UPSTREAM_UNAVAILABLE";
    /** 模型请求了不存在的工具名 */
    public static final String UNKNOWN_TOOL = "UNKNOWN_TOOL";

    private ToolErrorCode() {
    }
}
