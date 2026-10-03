package com.jobpilot.agent.tool;

import com.jobpilot.ai.ApprovalMode;
import com.jobpilot.ai.ToolErrorCode;
import com.jobpilot.ai.ToolExecutionContext;
import com.jobpilot.ai.ToolExecutionResult;
import com.jobpilot.ai.ToolResultStatus;
import com.jobpilot.application.ApplicationService;
import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.ApplicationEntity;
import com.jobpilot.domain.ApplicationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 四个投递类工具的约定。
 * <p>
 * 最要紧的一条：<b>模型在参数里伪造 {@code userId} 必须被忽略</b>——
 * 模型输出是不可信输入，租户只来自 runner 注入的执行上下文。
 */
class ApplicationToolTest {

    private ApplicationService applicationService;

    private ApplicationCreateTool createTool;
    private ApplicationUpdateTool updateTool;
    private ApplicationQueryTool queryTool;
    private ApplicationStatsTool statsTool;

    private static final String REAL_TENANT = "tenant-b-real";
    private static final String FORGED_TENANT = "tenant-victim";

    @BeforeEach
    void setUp() {
        applicationService = mock(ApplicationService.class);
        createTool = new ApplicationCreateTool(applicationService);
        updateTool = new ApplicationUpdateTool(applicationService);
        queryTool = new ApplicationQueryTool(applicationService);
        statsTool = new ApplicationStatsTool(applicationService);
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(REAL_TENANT, "conv-1", "trace-1", "call-1", ApprovalMode.AUTO);
    }

    // ── 租户隔离：四个工具都要挡住伪造的 userId ─────────────────

    @Test
    void createIgnoresForgedUserIdInArguments() {
        when(applicationService.create(any())).thenReturn(entity());

        createTool.execute(context(), """
                {"company":"ACME","position":"后端","status":"WISHLIST","userId":"%s"}"""
                .formatted(FORGED_TENANT));

        ArgumentCaptor<ApplicationService.CreateCommand> captor =
                ArgumentCaptor.forClass(ApplicationService.CreateCommand.class);
        verify(applicationService).create(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(REAL_TENANT);
    }

    @Test
    void statsNeverForwardsAForgedUserId() {
        when(applicationService.stats(any(), any())).thenReturn(new ApplicationService.Stats(0, Map.of()));

        statsTool.execute(context(), "{\"userId\":\"%s\"}".formatted(FORGED_TENANT));

        // 统计接口根本不接受 userId 参数——租户来自拦截器注入的 WHERE
        verify(applicationService).stats(null, null);
    }

    // ── 参数校验 ───────────────────────────────────────────────

    @Test
    void malformedJsonFailsWithoutThrowing() {
        ToolExecutionResult result = createTool.execute(context(), "{不是 json");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        verify(applicationService, never()).create(any());
    }

    @Test
    void missingRequiredFieldFails() {
        ToolExecutionResult result = createTool.execute(context(), "{\"company\":\"ACME\"}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(result.modelText()).contains("position");
    }

    @Test
    void unknownStatusFailsWithAllowedValues() {
        when(applicationService.create(any()))
                .thenThrow(new IllegalArgumentException("未知投递状态：BOGUS，允许值：[WISHLIST, APPLIED]"));

        ToolExecutionResult result = createTool.execute(context(),
                "{\"company\":\"ACME\",\"position\":\"后端\",\"status\":\"BOGUS\"}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        // 归因是「参数写错了」，不是泛泛的 TOOL_FAILED
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(result.modelText()).contains("允许值");
    }

    @Test
    void queryWithUnknownStatusFailsRatherThanReturningEmptyList() {
        // 返回空列表会让模型以为「用户没有记录」，实际是它的参数拼错了
        ToolExecutionResult result = queryTool.execute(context(), "{\"status\":\"NOPE\"}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        verify(applicationService, never()).query(any());
    }

    @Test
    void malformedDateFailsExplicitly() {
        ToolExecutionResult result = queryTool.execute(context(), "{\"from\":\"2026/10/03\"}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(result.modelText()).contains("yyyy-MM-dd");
    }

    // ── 更新他人记录：必须是失败，不是成功 ──────────────────────

    @Test
    void updateOnAnotherTenantsRecordFailsAsNotFound() {
        // 服务层已把「不存在」与「跨租户」合并成同一个 NOT_FOUND
        when(applicationService.update(anyString(), any()))
                .thenThrow(new ApiException(ErrorCode.NOT_FOUND, "投递记录不存在：x"));

        ToolExecutionResult result = updateTool.execute(context(), "{\"id\":\"x\",\"status\":\"OFFER\"}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.NOT_FOUND);
        // 消息里不能泄漏他人数据的存在性
        assertThat(result.modelText()).doesNotContain(FORGED_TENANT);
    }

    // ── 正常路径 ───────────────────────────────────────────────

    @Test
    void createReturnsBecauseOfIdForTheModelToReference() {
        when(applicationService.create(any())).thenReturn(entity());

        ToolExecutionResult result = createTool.execute(context(),
                "{\"company\":\"ACME\",\"position\":\"后端\",\"status\":\"APPLIED\",\"appliedAt\":\"2026-10-01\"}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.modelText()).contains("ID=app-1").contains("APPLIED");
    }

    @Test
    void updateDistinguishesAbsentFieldFromEmptyString() {
        when(applicationService.update(anyString(), any())).thenReturn(entity());

        // 传空串 = 要求清空（例如解除误关联的 JD）；缺省 = 不修改。
        // 两者若都变成 null，误关联的 JD 就永远解不掉。
        updateTool.execute(context(), "{\"id\":\"app-1\",\"jdDocumentId\":\"\"}");

        ArgumentCaptor<ApplicationService.Patch> captor =
                ArgumentCaptor.forClass(ApplicationService.Patch.class);
        verify(applicationService).update(anyString(), captor.capture());
        assertThat(captor.getValue().jdDocumentId()).isEmpty();
        assertThat(captor.getValue().company()).isNull();
    }

    @Test
    void queryReportsEmptyResultExplicitly() {
        when(applicationService.query(any())).thenReturn(List.of());

        ToolExecutionResult result = queryTool.execute(context(), "{}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.modelText()).contains("没有符合条件");
    }

    @Test
    void queryListsRecordsWithIdsSoTheModelCanReferenceThem() {
        when(applicationService.query(any())).thenReturn(List.of(entity()));

        ToolExecutionResult result = queryTool.execute(context(), "{\"status\":\"APPLIED\"}");

        assertThat(result.modelText()).contains("ID=app-1").contains("ACME");
    }

    @Test
    void statsReportsEveryStatusIncludingZeros() {
        when(applicationService.stats(any(), any())).thenReturn(new ApplicationService.Stats(3, Map.of(
                "APPLIED", 2L, "OFFER", 1L, "REJECTED", 0L)));

        ToolExecutionResult result = statsTool.execute(context(), "{}");

        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.modelText()).contains("投递总数：3").contains("REJECTED: 0");
    }

    @Test
    void allFourToolsAreAutoApprovedPerPrd() {
        // PRD-FP-2.2：投递类工具是低风险业务写入，无需审批。
        // HITL 只约束「写入长期记忆或知识库」的工具——别把规则扩大化。
        assertThat(List.of(createTool, updateTool, queryTool, statsTool))
                .allSatisfy(tool -> assertThat(tool.approvalMode()).isEqualTo(ApprovalMode.AUTO));
    }

    @Test
    void queryParsesStatusRegardlessOfCaseAndPadding() {
        when(applicationService.query(any())).thenReturn(List.of());

        queryTool.execute(context(), "{\"status\":\" applied \"}");

        ArgumentCaptor<ApplicationService.Query> captor =
                ArgumentCaptor.forClass(ApplicationService.Query.class);
        verify(applicationService).query(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ApplicationStatus.APPLIED);
    }

    private ApplicationEntity entity() {
        ApplicationEntity entity = new ApplicationEntity();
        entity.setId("app-1");
        entity.setUserId(REAL_TENANT);
        entity.setCompany("ACME");
        entity.setPosition("后端工程师");
        entity.setStatus("APPLIED");
        entity.setAppliedAt(LocalDate.of(2026, 10, 1));
        return entity;
    }
}
