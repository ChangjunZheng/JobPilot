package com.jobpilot.knowledge;

import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.config.RagProperties;
import com.jobpilot.domain.KbChunkEntity;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.mapper.KbChunkMapper;
import com.jobpilot.mapper.KbDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestServiceTest {

    private KbDocumentMapper documentMapper;
    private KbChunkMapper chunkMapper;
    private EmbeddingPort embeddingPort;
    private VectorStorePort vectorStore;
    private DocumentIngestService service;

    @BeforeEach
    void setUp() {
        documentMapper = mock(KbDocumentMapper.class);
        chunkMapper = mock(KbChunkMapper.class);
        embeddingPort = mock(EmbeddingPort.class);
        vectorStore = mock(VectorStorePort.class);
        RagProperties props = new RagProperties(
                "http://localhost:11434", "bge-m3", "qwen2.5:3b",
                "http://localhost:8000", "jobpilot_chunks", null, 500, 100, 5, 0.45,2);
        service = new DocumentIngestService(
                documentMapper, chunkMapper, new ChunkSplitter(), embeddingPort, vectorStore, props);

        // 模拟 MyBatis-Plus ASSIGN_UUID：insert 时补齐文档 ID
        doAnswer(invocation -> {
            KbDocumentEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId("doc-test");
            }
            return 1;
        }).when(documentMapper).insert(any(KbDocumentEntity.class));
        when(embeddingPort.embed(any())).thenReturn(List.of(0.1, 0.2));
    }

    @Test
    void happyPathMarksReadyWithIdempotentVectorIds() {
        KbDocumentEntity doc = service.ingest(new IngestCommand(
                "u1", "简历.md", "MARKDOWN", null, "# 技能\n\nJava / Spring Boot / RAG\n"));

        assertThat(doc.getStatus()).isEqualTo("READY");
        assertThat(doc.getChunkCount()).isPositive();

        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(vectorStore, atLeastOnce()).upsert(ids.capture(), any(), anyMap());
        // 向量 ID 形如 docId#seq#indexVersion，重建索引走 upsert 幂等
        assertThat(ids.getAllValues()).allMatch(id -> id.startsWith("doc-test#") && id.endsWith("#1"));
    }

    @Test
    void blankContentGoesToFailedWithoutChunks() {
        KbDocumentEntity doc = service.ingest(
                new IngestCommand("u1", "空.txt", "PLAIN_TEXT", null, "   "));

        assertThat(doc.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getErrorMessage()).contains("提取文本为空");
        verify(chunkMapper, never()).insert(any(KbChunkEntity.class));
    }

    @Test
    void embeddingOutageMarksFailed() {
        doThrow(new IllegalStateException("Ollama 离线")).when(embeddingPort).embed(any());

        KbDocumentEntity doc = service.ingest(new IngestCommand(
                "u1", "简历.md", "MARKDOWN", null, "# 技能\n\nJava\n"));

        assertThat(doc.getStatus()).isEqualTo("FAILED");
        assertThat(doc.getErrorMessage()).contains("Ollama 离线");
    }
}
