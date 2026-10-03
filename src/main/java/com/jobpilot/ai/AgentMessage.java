package com.jobpilot.ai;

import java.util.List;

/**
 * 对话消息（ARCHITECTURE.md §5.1 的 message 协议）。
 * <p>
 * <b>为什么是 sealed interface + 嵌套 record，而不是五个平级类</b>：它们是同一个封闭集合的
 * 不同角色，sealed 让消费方的 {@code switch} 穷尽性由编译器保证——新增一种角色而忘了处理，
 * 编译失败。这与 {@code ErrorCode} 刻意用不带 default 的 switch 是同一条原则。
 * <p>
 * <b>为什么嵌套而不与 Spring AI 的消息类同名</b>：那三个名字在
 * {@code scripts/check-arch.sh} 规则 2 的禁用词表里（用于捕捉供应商类型泄漏到业务层）。
 * 沿用会让构建门禁把自己的代码判成泄漏。守门人保持零假阳性比文档措辞一致更重要。
 *
 * @param text 文本内容；{@link ToolResult} 用它承载给模型看的结构化结果摘要
 */
public sealed interface AgentMessage {

    String text();

    /** 设定角色与全局规则，每轮 run 的第一条 */
    record System(String text) implements AgentMessage {
    }

    /** 用户输入，每轮 run 的起始消息 */
    record User(String text) implements AgentMessage {
    }

    /**
     * 模型的一轮输出。
     *
     * @param toolCalls 本轮请求的工具调用；为空表示这是最终答案
     */
    record Assistant(String text, List<ToolCall> toolCalls) implements AgentMessage {

        public Assistant {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    /**
     * 工具执行结果，回填给模型作为下一轮的输入。
     *
     * @param callId 对应 {@link ToolCall#id()}，模型据此把结果与请求配对
     * @param status 供应用层判断终态；模型只读 {@link #text()}
     */
    record ToolResult(String callId, String name, ToolResultStatus status, String text)
            implements AgentMessage {
    }
}
