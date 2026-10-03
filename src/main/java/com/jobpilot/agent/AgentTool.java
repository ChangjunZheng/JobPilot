package com.jobpilot.agent;

import com.jobpilot.ai.ApprovalMode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;

/**
 * 领域工具（PRD-FP-2.2）。
 * <p>
 * <b>实现约定（三条，违反任一即构成越权或数据损坏）</b>：
 * <ol>
 *   <li><b>租户只来自 {@link ToolExecutionContext#userId()}</b>，永远不从
 *       {@code execute} 的 {@code argumentsJson} 里读 —— 参数是模型输出，属于不可信输入；</li>
 *   <li><b>参数必须自己解析与校验</b> —— {@code inputSchema} 只约束模型输入，不构成服务端校验；</li>
 *   <li><b>抛异常是允许的</b> —— runner 会捕获并转成 {@code FAILED} 结果回填给模型，
 *       不会让异常穿透到 Controller（ARCHITECTURE.md §6）。</li>
 * </ol>
 * 工具之间互不知晓，由 {@link AgentToolRegistry} 按名字分发。
 */
public interface AgentTool {

    /** 工具名，模型据此调用；全局唯一 */
    String name();

    /** 给模型看的说明：什么时候该用它 */
    String description();

    /** JSON Schema，约束模型生成的参数形状；无参数时给一个空对象 schema */
    String inputSchema();

    /** 是否需要审批后才产生副作用 */
    ApprovalMode approvalMode();

    /**
     * 执行工具。
     *
     * @param context 租户与会话上下文，由 runner 注入——<b>这是 userId 的唯一来源</b>
     * @param argumentsJson 模型的原始参数 JSON，需自行解析
     */
    ToolExecutionResult execute(ToolExecutionContext context, String argumentsJson);
}
