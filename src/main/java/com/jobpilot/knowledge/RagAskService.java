package com.jobpilot.knowledge;

import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.RetrievalQuery;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 引用问答（ARCHITECTURE.md §4.2 尾段 / PRD-FP-1.4）：
 * 检索 → 证据不足直接拒答（不强行走模型）→ 有证据则带引用约束生成回答。
 * 引用列表由服务端从命中 Chunk 组装，模型只负责正文，杜绝虚构文档名。
 */
@Service
public class RagAskService {

    private static final String SYSTEM_PROMPT = """
            你是 JobPilot 求职知识库助手。规则：
            1. 只能依据【证据】回答，不得编造；
            2. 引用证据时使用其编号，形如 [1]、[2]；
            3. 证据不足以回答时，直接回答"知识库中缺少足够依据"，不要猜测；
            4. 回答使用简体中文，简洁分点。
            """;

    private final KnowledgeRetrievalService retrievalService;
    private final ChatPort chatPort;

    public RagAskService(KnowledgeRetrievalService retrievalService, ChatPort chatPort) {
        this.retrievalService = retrievalService;
        this.chatPort = chatPort;
    }

    public record AskAnswer(
            String answer,
            List<RetrievedChunk> evidence,
            RetrievalResult retrieval
    ) {
    }

    public AskAnswer ask(String userId, String question, int topK, String docType) {
        RetrievalResult retrieval = retrievalService.search(
                new RetrievalQuery(userId, question, topK, docType));
        List<RetrievedChunk> evidence = retrieval.items();
        if (evidence.isEmpty()) {
            String answer = retrieval.degraded()
                    ? "知识库中没有检索到相关证据（关键词降级模式），无法回答该问题。"
                    : "知识库中缺少足够依据，无法回答该问题。";
            return new AskAnswer(answer, evidence, retrieval);
        }
        String userPrompt = buildPrompt(question, evidence);
        String answer = chatPort.complete(SYSTEM_PROMPT, userPrompt);
        return new AskAnswer(answer, evidence, retrieval);
    }

    private String buildPrompt(String question, List<RetrievedChunk> evidence) {
        StringBuilder sb = new StringBuilder("【证据】\n");
        for (int i = 0; i < evidence.size(); i++) {
            RetrievedChunk chunk = evidence.get(i);
            sb.append('[').append(i + 1).append("] ")
                    .append('[').append(chunk.citation().display()).append("] ")
                    .append(chunk.text().strip()).append('\n');
        }
        sb.append("\n【问题】\n").append(question);
        return sb.toString();
    }
}
