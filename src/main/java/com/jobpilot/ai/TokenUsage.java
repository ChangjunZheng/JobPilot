package com.jobpilot.ai;

/**
 * token 消耗。本地 Ollama 一般不提供，故为可空。
 * <p>
 * I-2 只记录不计量：FP-10 的按租户计费属 I-3。
 */
public record TokenUsage(Integer inputTokens, Integer outputTokens) {
}
