package com.jobpilot.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 一次 Agent run 的追踪汇总；表独立于 kb_*，检索路径不读，故 trace 天然不进检索 */
@TableName("agent_trace")
public class AgentTraceEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String userId;
    private String conversationId;
    private LocalDateTime startedAt;
    /** NULL = 尚未收尾（异常退出或进程被杀）；finish 用 WHERE ended_at IS NULL 保证幂等 */
    private LocalDateTime endedAt;
    private Long durationMs;
    private String status;
    private String finishReason;
    /** RequestIdFilter 写入 MDC 的 requestId，用于与访问日志对账 */
    private String requestId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
