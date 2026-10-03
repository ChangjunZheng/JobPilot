package com.jobpilot.ai;

/**
 * 工具定义（发给模型的说明书）。
 * <p>
 * {@code inputSchema} 只约束<b>模型输入</b>，不构成服务端校验：模型可能不遵守 schema、
 * 也可能编造字段。工具入口仍需自行做类型与业务规则校验（ARCHITECTURE.md §5.2）。
 *
 * @param inputSchema JSON Schema 字符串，由工具自己给出；为空表示「无参数」
 */
public record ToolDefinition(String name, String description, String inputSchema) {
}
