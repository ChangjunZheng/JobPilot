package com.jobpilot.ai;

/** 工具执行结果状态。{@code PENDING_APPROVAL} 会让 runner 立即结束本轮 run。 */
public enum ToolResultStatus {
    SUCCESS,
    FAILED,
    /** 已落审批草稿，副作用尚未执行；runner 见到它就结束本轮 */
    PENDING_APPROVAL
}
