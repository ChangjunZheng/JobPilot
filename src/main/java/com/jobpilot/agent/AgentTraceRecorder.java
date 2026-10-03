package com.jobpilot.agent;

import com.jobpilot.domain.AgentTraceEntity;
import com.jobpilot.domain.AgentTraceStepEntity;
import com.jobpilot.mapper.AgentTraceMapper;
import com.jobpilot.mapper.AgentTraceStepMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * trace 写入（PRD-FP-3.1）。
 * <p>
 * <b>每步独立事务（REQUIRES_NEW）</b>：trace 是排查用的旁路，不该因为主流程回滚而丢失——
 * 「工具失败时到底发生了什么」正是最需要 trace 的时刻。
 * <p>
 * <b>写 trace 失败绝不影响主流程</b>：整个方法体包在 try-catch 里。trace 挂了顶多少一条排查线索，
 * 而让一次正常的问答因为追踪失败而报错是本末倒置。
 */
@Service
public class AgentTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceRecorder.class);

    private static final int SUMMARY_MAX = 1024;
    private static final int ARGUMENTS_MAX = 2048;

    private final AgentTraceMapper traceMapper;
    private final AgentTraceStepMapper stepMapper;

    public AgentTraceRecorder(AgentTraceMapper traceMapper, AgentTraceStepMapper stepMapper) {
        this.traceMapper = traceMapper;
        this.stepMapper = stepMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(String traceId, String userId, String conversationId, String requestId) {
        try {
            AgentTraceEntity trace = new AgentTraceEntity();
            trace.setId(traceId);
            trace.setUserId(userId);
            trace.setConversationId(conversationId);
            trace.setRequestId(requestId);
            // 先落一行 RUNNING，让「跑到一半被kill」留下可查的痕迹
            trace.setStatus("RUNNING");
            trace.setFinishReason("PENDING");
            traceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("trace 开始记录失败（不影响主流程）traceId={}", traceId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String traceId, String userId, String conversationId,
                       long durationMs, String status, String finishReason) {
        try {
            AgentTraceEntity trace = new AgentTraceEntity();
            trace.setId(traceId);
            trace.setUserId(userId);
            trace.setConversationId(conversationId);
            trace.setEndedAt(LocalDateTime.now());
            trace.setDurationMs(durationMs);
            trace.setStatus(status);
            trace.setFinishReason(finishReason);
            // WHERE ended_at IS NULL：重复收尾不会覆盖先到的那次
            traceMapper.finish(trace);
        } catch (Exception e) {
            log.warn("trace 收尾记录失败（不影响主流程）traceId={}", traceId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void step(String traceId, String userId, int iteration, String kind, String name,
                     String status, long durationMs, String summary, String argumentsJson, String errorCode) {
        try {
            AgentTraceStepEntity step = new AgentTraceStepEntity();
            step.setTraceId(traceId);
            step.setUserId(userId);
            step.setIteration(iteration);
            step.setKind(kind);
            step.setName(name);
            step.setStatus(status);
            step.setDurationMs(durationMs);
            step.setSummary(truncate(summary, SUMMARY_MAX));
            step.setArgumentsJson(truncate(argumentsJson, ARGUMENTS_MAX));
            step.setErrorCode(errorCode);
            stepMapper.insert(step);
        } catch (Exception e) {
            log.warn("trace 步骤记录失败（不影响主流程）traceId={} step={}", traceId, name, e);
        }
    }

    /**
     * 截断到上限。
     * <p>
     * trace 存的是<b>摘要</b>不是原文——否则 trace 就成了用户私密材料的第二份副本，
     * 而它并不参与检索、也不该成为绕过 READY 过滤的旁路（ARCHITECTURE.md §7）。
     */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
