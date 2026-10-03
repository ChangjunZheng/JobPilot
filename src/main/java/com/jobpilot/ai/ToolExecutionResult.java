package com.jobpilot.ai;

import java.util.List;

/**
 * 工具执行结果。
 * <p>
 * <b>工具异常不得外抛到 Controller</b>（ARCHITECTURE.md §6）：runner 捕获后转成
 * {@code status=FAILED} 的本 record 回填给模型，由模型在预算内决定重试还是向用户说明。
 *
 * @param modelText 给模型看的结构化摘要（会回填进对话历史）
 * @param data      结构化数据，供应用层使用，<b>不进对话历史</b>（避免污染上下文）
 * @param citations 命中的引用，JD 分析类工具用于「每个差距都要有材料依据」
 * @param errorCode 模型面错误码，<b>刻意不复用</b> {@code com.jobpilot.common.ErrorCode}：
 *                  后者绑定 HTTP 状态映射，塞入工具内部错误会污染那个穷尽 switch。
 *                  取值见 {@code ToolErrorCode}（封闭集合）
 */
public record ToolExecutionResult(
        String callId,
        String name,
        ToolResultStatus status,
        String modelText,
        Object data,
        List<Citation> citations,
        String errorCode
) {

    public ToolExecutionResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public static ToolExecutionResult success(String callId, String name, String modelText, Object data) {
        return new ToolExecutionResult(callId, name, ToolResultStatus.SUCCESS, modelText, data, List.of(), null);
    }

    public static ToolExecutionResult success(String callId, String name, String modelText,
                                              Object data, List<Citation> citations) {
        return new ToolExecutionResult(callId, name, ToolResultStatus.SUCCESS, modelText, data, citations, null);
    }

    public static ToolExecutionResult failed(String callId, String name, String modelText, String errorCode) {
        return new ToolExecutionResult(callId, name, ToolResultStatus.FAILED, modelText, null, List.of(), errorCode);
    }

    public static ToolExecutionResult pendingApproval(String callId, String name, String modelText, Object data) {
        return new ToolExecutionResult(callId, name, ToolResultStatus.PENDING_APPROVAL, modelText, data,
                List.of(), null);
    }
}
