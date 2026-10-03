package com.jobpilot.ai;

/**
 * 对话生成端口。
 * <p>
 * 两个方法的分工：{@link #complete} 是 I-0 的单轮问答（{@code RagAskService} 在用，不做工具调用），
 * {@link #chat} 是 I-2 的多轮 + 工具定义。保留前者是为了让既有问答链路一行不改。
 * <p>
 * <b>端口不执行工具。</b>{@link #chat} 只会带回模型的 {@link ToolCall} 请求，
 * 执行由 {@code AgentRunner} 负责——预算、trace、租户上下文注入与 HITL 短路都必须在端口之外。
 */
public interface ChatPort {

    /** 单轮问答：设定角色 + 一次用户输入 → 一段回答 */
    String complete(String systemPrompt, String userPrompt);

    /** 多轮对话；可能带回工具调用请求，但<b>不执行</b>它们 */
    ChatCompletion chat(ChatRequest request);
}
