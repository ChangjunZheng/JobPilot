package com.jobpilot.ai;

/**
 * 一次 run 的终止原因。
 * <p>
 * {@code TOOL_CALLS} 不是终态——它表示「模型还要继续调工具」，runner 据此进入下一轮。
 * 另外三个才是真正的结束，且都要写进 trace。
 */
public enum FinishReason {
    /** 模型给出最终答案 */
    STOP,
    /** 模型请求了工具调用，runner 应继续下一轮 */
    TOOL_CALLS,
    /** 轮次或工具调用次数耗尽（ARCHITECTURE §6 的预算约束） */
    BUDGET_EXHAUSTED,
    /** 模型不可用或返回异常，已转成结构化失败而非抛出 */
    ERROR
}
