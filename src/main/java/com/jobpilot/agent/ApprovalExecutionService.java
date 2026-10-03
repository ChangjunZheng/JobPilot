package com.jobpilot.agent;

import com.jobpilot.common.ApiException;
import com.jobpilot.common.ErrorCode;
import com.jobpilot.domain.AgentApprovalDraftEntity;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.knowledge.DocumentIngestService;
import com.jobpilot.knowledge.IngestCommand;
import com.jobpilot.security.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审批通过后的副作用执行（ARCHITECTURE.md §4.4）。
 * <p>
 * <b>只认 {@code save_jd_analysis_to_kb} 这一种工具</b>：这是当前唯一有副作用的工具。
 * 白名单而不是 if-else 分派，是为了将来加工具时「忘记加分支」会立刻抛错，而不是静默不执行。
 * <p>
 * <b>事务边界</b>：{@code enqueue} 的插入与草稿状态翻转必须在同一事务里，
 * 否则会出现「文档已建、草稿仍 PENDING」的窗口，重复审批就会建出第二份文档。
 */
@Service
public class ApprovalExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExecutionService.class);

    private static final String TOOL_SAVE_JD = "save_jd_analysis_to_kb";

    private final ApprovalDraftService draftService;
    private final DocumentIngestService ingestService;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApprovalExecutionService(ApprovalDraftService draftService, DocumentIngestService ingestService) {
        this.draftService = draftService;
        this.ingestService = ingestService;
    }

    /**
     * 审批通过：抢占审批权 → 执行副作用 → 回填结果引用。
     * <p>
     * 整体一个事务：抢占失败（已被处理）时直接返回，<b>不执行任何副作用</b>——这是重复审批
     * 幂等的关键。
     *
     * @return 新建文档的 ID；已处理过则返回该草稿上已记录的 resultRef
     */
    @Transactional
    public String approve(String draftId) {
        String userId = UserContext.require();
        AgentApprovalDraftEntity draft = draftService.get(draftId);
        if (draft == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "审批草稿不存在：" + draftId);
        }
        // 抢占审批权：锁行 + 状态检查。false = 已被处理，按幂等返回，绝不重复执行副作用。
        if (!draftService.claimForApproval(draftId)) {
            return draft.getResultRef();
        }
        // 抢占成功，draft 已是 APPROVED；此时才真正产生副作用
        if (!TOOL_SAVE_JD.equals(draft.getToolName())) {
            // 理论上不可达：只有该工具会落草稿。留作白名单护栏，将来加工具时忘了实现会立刻暴露。
            log.error("审批执行遇到未实现的工具 tool={} draftId={}", draft.getToolName(), draftId);
            throw new ApiException(ErrorCode.BAD_REQUEST, "暂不支持审批该工具：" + draft.getToolName());
        }
        String documentId = saveJdAnalysis(userId, draft.getPayloadJson());
        draftService.recordResult(draftId, documentId);
        log.info("审批执行完成 draftId={} documentId={}", draftId, documentId);
        return documentId;
    }

    /** 拒绝：只改状态，不产生任何副作用 */
    @Transactional
    public void reject(String draftId) {
        draftService.reject(draftId);
    }

    /**
     * 把 JD 分析结果作为一篇 Markdown 文档入队。
     * <p>
     * 用 {@code enqueue}（落 PENDING）而不是直接写 READY：审批的语义是「允许写入」，
     * 索引仍走正常的异步链路，不给审批开后门。
     */
    private String saveJdAnalysis(String userId, String payloadJson) {
        JsonNode payload;
        try {
            payload = mapper.readTree(payloadJson == null ? "{}" : payloadJson);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "审批载荷不是合法 JSON，无法执行");
        }
        String content = payload.path("content").asText("").strip();
        if (content.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "审批载荷缺少 content，无法执行");
        }
        String name = payload.path("name").asText("JD分析.md");
        // userId 来自认证上下文，不是载荷——载荷是模型写的
        KbDocumentEntity doc = ingestService.enqueue(
                new IngestCommand(userId, name, "MARKDOWN", "jd-analysis", content));
        return doc.getId();
    }
}
