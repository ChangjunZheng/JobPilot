package com.jobpilot.ai;

import java.util.List;

/**
 * 一次模型调用的结果。
 * <p>
 * <b>命名说明</b>：刻意避开 Spring AI 那几个消息/响应类名——它们在
 * {@code scripts/check-arch.sh} 规则 2 的禁用词表里（用于捕捉供应商类型泄漏到业务层）。
 * 本项目自己的协议 record 若沿用同名，门禁会把它们误判成泄漏而让构建失败。
 *
 * @param content    文本内容；模型只请求工具时可能为空串
 * @param toolCalls  本轮请求的工具调用；为空表示这是最终答案
 * @param usage      供应商提供时才有；本地 Ollama 通常为 null
 */
public record ChatCompletion(
        String content,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        TokenUsage usage,
        String provider,
        String model
) {

    public ChatCompletion {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
