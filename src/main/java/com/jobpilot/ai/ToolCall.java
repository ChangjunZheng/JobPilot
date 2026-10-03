package com.jobpilot.ai;

/**
 * 模型请求的一次工具调用。
 * <p>
 * <b>{@code arguments} 是模型的原始 JSON 字符串，不做解析。</b>解析责任在工具自己身上：
 * 只有工具知道自己的参数形状，而模型输出是不可信输入——先按 JSON 解析成工具自己的
 * record、再逐字段做类型与业务校验，是唯一安全的路径。
 * <p>
 * 这里刻意没有 {@code userId} 之类的字段：租户只来自 {@link ToolExecutionContext}，
 * 由 runner 注入。模型就算在参数里塞 {@code userId}，工具也必须忽略（见工具实现）。
 */
public record ToolCall(String id, String name, String arguments) {
}
