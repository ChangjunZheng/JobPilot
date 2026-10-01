package com.jobpilot.knowledge;

import com.jobpilot.ai.ChatPort;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.RetrievalResult;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.config.RagProperties;
import com.jobpilot.mapper.KbChunkMapper;
import com.jobpilot.mapper.KbDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTest {

    private KbChunkMapper chunkMapper;
    private com.jobpilot.mapper.KbDocumentMapper documentMapper;
    private EmbeddingPort embeddingPort;
    private VectorStorePort vectorStore;
    private ChatPort chatPort;
    private KnowledgeRetrievalService retrievalService;
    private RagAskService askService;

    @BeforeEach
    void setUp() {
        chunkMapper = mock(KbChunkMapper.class);
        documentMapper = mock(com.jobpilot.mapper.KbDocumentMapper.class);
        embeddingPort = mock(EmbeddingPort.class);
        vectorStore = mock(VectorStorePort.class);
        chatPort = mock(ChatPort.class);
        RagProperties props = new RagProperties(
                "http://localhost:11434", "bge-m3", "qwen2.5:3b",
                "http://localhost:8000", "jobpilot_chunks", null, 500, 100, 5, 0.45,2);
        retrievalService = new KnowledgeRetrievalService(
                chunkMapper, documentMapper, embeddingPort, vectorStore, props);
        askService = new RagAskService(retrievalService, chatPort);
    }

    @Test
    void vectorHitAboveThresholdReturnsChunk() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.search(any(), anyInt(), anyMap()))
                .thenReturn(List.of(new VectorStorePort.VectorMatch("doc1#0#1", 0.8)));
        when(chunkMapper.selectByIds(any())).thenReturn(List.of(chunk("doc1#0#1")));
        when(documentMapper.selectList(any())).thenReturn(List.of(readyDoc()));

        RetrievalResult result = retrievalService.search(
                new com.jobpilot.ai.RetrievalQuery("u1", "会用 RAG 吗", 5, null));

        assertThat(result.searchMode()).isEqualTo(com.jobpilot.ai.SearchMode.VECTOR);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).score()).isEqualTo(0.8);
    }

    @Test
    void belowThresholdReturnsEmptyWithoutLLM() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.search(any(), anyInt(), anyMap()))
                .thenReturn(List.of(new VectorStorePort.VectorMatch("doc1#0#1", 0.2)));

        RagAskService.AskAnswer answer = askService.ask("u1", "完全无关的问题", 5, null);

        assertThat(answer.retrieval().items()).isEmpty();
        assertThat(answer.answer()).contains("缺少足够依据");
        verifyNoInteractions(chatPort);
    }

    @Test
    void chromaOutageDegradesToKeywordSearch() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.search(any(), anyInt(), anyMap()))
                .thenThrow(new IllegalStateException("Chroma 不可用"));
        // 同时命中 "RAG" 与 "经验" 两个关键词，达到 keywordMinHits = 2
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(chunk("doc1#0#1", "熟悉 RAG 开发，有 3 年经验")));
        when(documentMapper.selectList(any())).thenReturn(List.of(readyDoc()));

        RagAskService.AskAnswer answer = askService.ask("u1", "RAG 经验", 5, null);

        assertThat(answer.retrieval().degraded()).isTrue();
        assertThat(answer.retrieval().searchMode())
                .isEqualTo(com.jobpilot.ai.SearchMode.KEYWORD_FALLBACK);
        assertThat(answer.retrieval().items()).hasSize(1);
        // 降级命中且过闸门后仍会走生成，并带上降级证据
        verify(chatPort).complete(anyString(), anyString());
    }

    @Test
    void keywordFallbackBelowMinHitsRefusesWithoutLLM() {
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1));
        when(vectorStore.search(any(), anyInt(), anyMap()))
                .thenThrow(new IllegalStateException("Chroma 不可用"));
        // chunk 只命中 "RAG" 一个关键词，低于 keywordMinHits = 2 → 不算证据
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk("doc1#0#1")));

        RagAskService.AskAnswer answer = askService.ask("u1", "RAG 经验", 5, null);

        assertThat(answer.retrieval().degraded()).isTrue();
        assertThat(answer.retrieval().items()).isEmpty();
        assertThat(answer.answer()).contains("没有检索到相关证据");
        verifyNoInteractions(chatPort);
    }

    @Test
    void fullVectorOutageAlsoDegrades() {
        when(embeddingPort.embed(any())).thenThrow(new IllegalStateException("Ollama 离线"));
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        RetrievalResult result = retrievalService.search(
                new com.jobpilot.ai.RetrievalQuery("u1", "查询", 0, null));

        assertThat(result.degraded()).isTrue();
    }

    @Test
    void keywordExtractionHandlesChineseAndEnglish() {
        List<String> keywords = retrievalService.extractKeywords("3年 Java 经验，熟悉 Spring Boot");

        assertThat(keywords).contains("3年", "Java", "经验", "熟悉");
        assertThat(keywords.size()).isLessThanOrEqualTo(12);
    }

    private com.jobpilot.domain.KbDocumentEntity readyDoc() {
        com.jobpilot.domain.KbDocumentEntity doc = new com.jobpilot.domain.KbDocumentEntity();
        doc.setId("doc1");
        doc.setUserId("u1");
        doc.setStatus("READY");
        return doc;
    }

    private com.jobpilot.domain.KbChunkEntity chunk(String vectorId) {
        return chunk(vectorId, "熟悉 RAG 与 Agent 开发");
    }

    private com.jobpilot.domain.KbChunkEntity chunk(String vectorId, String text) {
        com.jobpilot.domain.KbChunkEntity chunk = new com.jobpilot.domain.KbChunkEntity();
        chunk.setVectorId(vectorId);
        chunk.setDocumentId("doc1");
        chunk.setUserId("u1");
        chunk.setDocName("简历.md");
        chunk.setDocType("MARKDOWN");
        chunk.setSectionPath("技能");
        chunk.setSeq(0);
        chunk.setText(text);
        chunk.setCharStart(0);
        chunk.setCharEnd(10);
        chunk.setIndexVersion(1);
        return chunk;
    }
}
