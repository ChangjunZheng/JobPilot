package com.jobpilot.ai;

import java.time.Duration;
import java.util.List;

/**
 * 多轮对话 + 工具定义的请求（ARCHITECTURE.md §5.1）。
 * <p>
 * <b>只描述「发什么」，不描述「怎么执行」</b>：本请求可能带回 {@link ToolCall}，
 * 但工具的执行完全不在端口内——由 {@code AgentRunner} 负责。端口若自己执行工具，
 * 预算、trace 与租户上下文就都被关进了适配器里。
 *
 * @param model   为 null 时由适配器回落到配置里的模型
 * @param timeout 为 null 时由 runner 用自己的超时策略包裹
 */
public record ChatRequest(
        List<AgentMessage> messages,
        List<ToolDefinition> tools,
        String model,
        Double temperature,
        Integer maxTokens,
        Duration timeout
) {

    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
