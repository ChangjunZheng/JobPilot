package com.jobpilot.ai;

/**
 * 工具的副作用审批模式。
 * <p>
 * 对应 ARCHITECTURE.md §4.4：写入类工具返回 {@code PENDING_APPROVAL} 草稿，
 * 本轮 run 直接结束，用户稍后经独立接口审批时才真正执行副作用。
 * <b>刻意不做「运行中暂停等人点确认」</b>——那会引入连接超时、租户占线程、暂停态存哪三个新问题
 * （也正是 LangGraph4j 最强能力所解决的、而本项目特意设计掉的问题）。
 */
public enum ApprovalMode {
    /** 无副作用或低风险写入，工具可直接执行 */
    AUTO,
    /** 产生写入副作用，必须先落草稿并等待用户审批 */
    REQUIRE_APPROVAL
}
