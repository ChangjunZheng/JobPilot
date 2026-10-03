package com.jobpilot.ai;

/**
 * 工具执行上下文——<b>租户身份的唯一来源</b>。
 * <p>
 * 由 {@code AgentRunner} 在执行前创建并注入，工具<b>只能</b>从这里取 {@link #userId()}。
 * 工具参数里的任何 {@code userId} 都是模型编造的输入，必须忽略（ARCHITECTURE.md §4.3）。
 *
 * @param callId       本次调用的 ID；工具据此构造结果，模型靠它把结果与请求配对
 * @param approvalMode 该工具是否需要审批；工具据此决定直接执行还是落草稿
 */
public record ToolExecutionContext(
        String userId,
        String conversationId,
        String traceId,
        String callId,
        ApprovalMode approvalMode
) {
}
