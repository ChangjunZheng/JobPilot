package com.jobpilot.knowledge;

import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.common.ApiException;
import com.jobpilot.config.RagProperties;
import com.jobpilot.domain.KbChunkEntity;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.mapper.KbChunkMapper;
import com.jobpilot.mapper.KbDocumentMapper;
import com.jobpilot.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 文档导入与索引（ARCHITECTURE.md §4.1 / §7.3 的可重试状态机）：
 * PROCESSING → Chunk 落 MySQL → Ollama 嵌入 → Chroma upsert → READY；
 * 任一步失败标记 FAILED 并记录原因，不把半成品放进检索。
 * 向量 ID = docId#seq#indexVersion，重复执行走 upsert，天然幂等。
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private final KbDocumentMapper documentMapper;
    private final KbChunkMapper chunkMapper;
    private final ChunkSplitter chunkSplitter;
    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStore;
    private final RagProperties props;

    public DocumentIngestService(KbDocumentMapper documentMapper,
                                 KbChunkMapper chunkMapper,
                                 ChunkSplitter chunkSplitter,
                                 EmbeddingPort embeddingPort,
                                 VectorStorePort vectorStore,
                                 RagProperties props) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.chunkSplitter = chunkSplitter;
        this.embeddingPort = embeddingPort;
        this.vectorStore = vectorStore;
        this.props = props;
    }

    /**
     * 服务层命令保留 userId 只是为了让用例可脱离 HTTP/ThreadLocal 测试；
     * 真正的 Controller 已从 UserContext 派生身份（客户端不能传入）。
     * 这里再做一次一致性校验，防止未来新增调用方绕过 Controller 注入另一个租户。
     */
    public KbDocumentEntity ingest(IngestCommand command) {
        validate(command);
        String contextUserId = UserContext.get();
        if (contextUserId != null && !contextUserId.equals(command.userId())) {
            throw new com.jobpilot.common.UnauthorizedException("租户上下文与导入身份不一致");
        }
        KbDocumentEntity doc = new KbDocumentEntity();
        doc.setUserId(command.userId());
        doc.setName(command.name());
        doc.setDocType(command.docType());
        doc.setTags(command.tags());
        doc.setStatus("PROCESSING");
        doc.setIndexVersion(1);
        doc.setChunkCount(0);
        documentMapper.insert(doc);

        try {
            if (command.content() == null || command.content().isBlank()) {
                throw new IllegalArgumentException("提取文本为空，无可索引 Chunk");
            }
            List<ChunkPart> parts = chunkSplitter.split(
                    command.content(), doc.getDocType(), props.chunkSize(), props.chunkOverlap());
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("提取文本为空，无可索引 Chunk");
            }
            for (ChunkPart part : parts) {
                indexChunk(doc, part);
            }
            doc.setStatus("READY");
            doc.setChunkCount(parts.size());
            doc.setErrorMessage(null);
        } catch (Exception e) {
            log.warn("文档索引失败 documentId={}", doc.getId(), e);
            doc.setStatus("FAILED");
            doc.setErrorMessage(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        documentMapper.updateById(doc);
        return doc;
    }

    /** 状态查询；不存在时抛 404 语义的 ApiException */
    public KbDocumentEntity document(String id) {
        KbDocumentEntity doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new ApiException("NOT_FOUND", "文档不存在：" + id);
        }
        return doc;
    }

    private void indexChunk(KbDocumentEntity doc, ChunkPart part) {
        String vectorId = doc.getId() + "#" + part.seq() + "#" + doc.getIndexVersion();
        
        List<Double> vector = embeddingPort.embed(part.text());

        // 1.先写 Chroma
        vectorStore.upsert(vectorId, vector, Map.of(
                "user_id", doc.getUserId(),
                "document_id", doc.getId(),
                "doc_type", doc.getDocType(),
                "index_version", doc.getIndexVersion()));

        KbChunkEntity chunk = new KbChunkEntity();
        chunk.setVectorId(vectorId);
        chunk.setDocumentId(doc.getId());
        chunk.setUserId(doc.getUserId());
        chunk.setDocName(doc.getName());
        chunk.setDocType(doc.getDocType());
        chunk.setSectionPath(part.sectionPath());
        chunk.setSeq(part.seq());
        chunk.setText(part.text());
        chunk.setCharStart(part.charStart());
        chunk.setCharEnd(part.charEnd());
        chunk.setIndexVersion(doc.getIndexVersion());
        
        // 2.再写 MySQL
        chunkMapper.insert(chunk); // 主键已赋值，重复执行时 insert 会撞主键 —— M-1 用整文档重导代替部分重试
    }

    private void validate(IngestCommand command) {
        if (command.userId() == null || command.userId().isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new IllegalArgumentException("文档名称不能为空");
        }
        if (!"MARKDOWN".equals(command.docType()) && !"PLAIN_TEXT".equals(command.docType())) {
            throw new IllegalArgumentException("M-1 仅支持 MARKDOWN / PLAIN_TEXT，收到：" + command.docType());
        }
    }

    private String truncate(String message) {
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
