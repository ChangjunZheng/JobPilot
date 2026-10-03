package com.jobpilot.controller;

import com.jobpilot.agent.AgentRunner;
import com.jobpilot.agent.ApprovalDraftService;
import com.jobpilot.agent.ApprovalExecutionService;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ApiResponse;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.AgentApprovalDraftEntity;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * I-2 Agent 与审批接口。
 * <p>
 * <b>请求体一律不含 {@code userId}</b>：身份只从 JWT 解析、经 {@code UserContext} 传递。
 * 客户端即使在 body 里塞一个 {@code userId}，也不会被绑定——record 里根本没这个字段。
 */
@RestController
@RequestMapping("/api/v1/agent")
@Validated
public class AgentController {

    private final AgentRunner agentRunner;
    private final ApprovalExecutionService approvalExecutionService;
    private final ApprovalDraftService approvalDraftService;

    public AgentController(AgentRunner agentRunner,
                           ApprovalExecutionService approvalExecutionService,
                           ApprovalDraftService approvalDraftService) {
        this.agentRunner = agentRunner;
        this.approvalExecutionService = approvalExecutionService;
        this.approvalDraftService = approvalDraftService;
    }

    public record RunRequest(
            @NotBlank String message,
            /** 可选；为空时服务端新建会话 */
            String conversationId
    ) {
    }

    public record StepView(int iteration, String kind, String name, long durationMs, String status, String summary) {

        static StepView from(AgentRunner.Step step) {
            return new StepView(step.iteration(), step.kind(), step.name(),
                    step.durationMs(), step.status(), step.summary());
        }
    }

    public record RunResponse(
            String traceId,
            String conversationId,
            String answer,
            String finishReason,
            List<StepView> steps,
            /** 待审批草稿 ID；非空表示本轮因 HITL 结束，副作用尚未执行 */
            List<String> draftIds
    ) {

        static RunResponse from(AgentRunner.RunResult result) {
            return new RunResponse(result.traceId(), result.conversationId(), result.answer(),
                    result.finishReason().name(), result.steps().stream().map(StepView::from).toList(),
                    result.draftIds());
        }
    }

    public record ApprovalResponse(String draftId, String status, String resultRef) {

        static ApprovalResponse from(String draftId, AgentApprovalDraftEntity draft) {
            return new ApprovalResponse(draftId, draft.getStatus(), draft.getResultRef());
        }
    }

    /** 发起一次 Agent 对话；模型自行决定是否调用工具 */
    @PostMapping("/run")
    public ApiResponse<RunResponse> run(@RequestBody @Validated RunRequest request) {
        return ApiResponse.ok(RunResponse.from(
                agentRunner.run(new AgentRunner.RunRequest(request.conversationId(), request.message()))));
    }

    /** 审批通过：执行写入副作用；重复审批幂等，不会建出第二份文档 */
    @PostMapping("/approvals/{draftId}/approve")
    public ApiResponse<ApprovalResponse> approve(@PathVariable String draftId) {
        String documentId = approvalExecutionService.approve(draftId);
        return ApiResponse.ok(new ApprovalResponse(draftId, "APPROVED", documentId));
    }

    /** 拒绝：只改状态，不产生任何副作用 */
    @PostMapping("/approvals/{draftId}/reject")
    public ApiResponse<ApprovalResponse> reject(@PathVariable String draftId) {
        approvalExecutionService.reject(draftId);
        return ApiResponse.ok(new ApprovalResponse(draftId, "REJECTED", null));
    }

    /**
     * 查询草稿状态；跨租户访问与「不存在」不可区分（租户拦截器过滤，查不到即 404）。
     */
    @GetMapping("/approvals/{draftId}")
    public ApiResponse<ApprovalResponse> approval(@PathVariable String draftId) {
        AgentApprovalDraftEntity draft = approvalDraftService.get(draftId);
        if (draft == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "审批草稿不存在：" + draftId);
        }
        return ApiResponse.ok(ApprovalResponse.from(draftId, draft));
    }
}
