package com.jobpilot.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.ai.VectorStorePort;
import com.jobpilot.config.IngestProperties;
import com.jobpilot.config.RagProperties;
import com.jobpilot.domain.KbChunkEntity;
import com.jobpilot.domain.KbDocumentEntity;
import com.jobpilot.mapper.KbChunkMapper;
import com.jobpilot.mapper.KbDocumentMapper;
import com.jobpilot.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文档导入（ARCHITECTURE.md §4.1 的可重试状态机，I-1c 起拆为两段）：
 * <ul>
 *   <li>{@link #enqueue}：请求线程只做校验并落一行 {@code PENDING}，立即返回——索引移出请求线程；</li>
 *   <li>{@link #process}：worker 对一个已认领（PROCESSING）的任务执行单次尝试，
 *       切分 → 逐 Chunk 嵌入 → Chroma upsert → Chunk 落库 → READY。</li>
 * </ul>
 * 失败分流（fail-loud 的另一半）：{@code IllegalArgumentException}（空白内容等确定性校验失败）
 * 直接 FAILED 不重试——重试不可能修好它；其余异常视为暂态（向量库/嵌入模型不可达等），
 * 按 {@code retry_count} 重排队并指数退避，超过上限才 FAILED。
 * 任一失败路径都会清掉本次写入的 Chunk：半成品不得进入检索，也不得残留到下一次重试（vector_id 会撞主键）。
 * <p>
 * {@code process} 必须在 worker 设置好 {@code UserContext} 后调用——
 * Chunk 写入依赖租户拦截器注入 user_id，这也是上下文显式传递约定的落地处。
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
    private final IngestProperties ingestProps;

    public DocumentIngestService(KbDocumentMapper documentMapper,
                                 KbChunkMapper chunkMapper,
                                 ChunkSplitter chunkSplitter,
                                 EmbeddingPort embeddingPort,
                                 VectorStorePort vectorStore,
                                 RagProperties props,
                                 IngestProperties ingestProps) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.chunkSplitter = chunkSplitter;
        this.embeddingPort = embeddingPort;
        this.vectorStore = vectorStore;
        this.props = props;
        this.ingestProps = ingestProps;
    }

    /**
     * 队列入队：校验通过后落一行 PENDING 即返回（202 语义），索引交给 worker。
     * <p>
     * 服务层命令保留 userId 只是为了让用例可脱离 HTTP/ThreadLocal 测试；
     * 真正的 Controller 已从 UserContext 派生身份（客户端不能传入）。
     * 这里再做一次一致性校验，防止未来新增调用方绕过 Controller 注入另一个租户。
     */
    public KbDocumentEntity enqueue(IngestCommand command) {
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
        doc.setContent(command.content());
        doc.setStatus("PENDING");
        doc.setIndexVersion(1);
        doc.setChunkCount(0);
        doc.setRetryCount(0);
        documentMapper.insert(doc);
        return doc;
    }

    /** 状态查询；不存在时抛 404 语义的 ApiException（跨租户同样表现为不存在） */
    public KbDocumentEntity document(String id) {
        KbDocumentEntity doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new com.jobpilot.common.ApiException(com.jobpilot.common.ErrorCode.NOT_FOUND, "文档不存在：" + id);
        }
        return doc;
    }

    /**
     * 执行一次已认领任务的索引尝试。失败不抛出——任务的去向（READY / PENDING 重排队 / FAILED）
     * 全部落到行上，worker 循环不因单个任务中断。
     */
    public void process(KbDocumentEntity claimed) {
        if (!"PROCESSING".equals(claimed.getStatus())) {
            throw new IllegalStateException(
                    "process 只接受已认领（PROCESSING）的任务，收到：" + claimed.getStatus());
        }
        deleteChunksOf(claimed); // 重试幂等：先清掉上一次尝试可能残留的 Chunk，vector_id 才不会撞主键

        try {
            String content = claimed.getContent();
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("提取文本为空，无可索引 Chunk");
            }
            List<ChunkPart> parts = chunkSplitter.split(
                    content, claimed.getDocType(), props.chunkSize(), props.chunkOverlap());
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("提取文本为空，无可索引 Chunk");
            }
            for (ChunkPart part : parts) {
                indexChunk(claimed, part);
            }
            claimed.setStatus("READY");
            claimed.setChunkCount(parts.size());
            claimed.setErrorMessage(null);
            claimed.setNextRetryAt(null);
            updateGuardedByProcessing(claimed);
            log.info("文档索引完成 documentId={} chunks={}", claimed.getId(), parts.size());
        } catch (IllegalArgumentException e) {
            failPermanently(claimed, e);
        } catch (Exception e) {
            requeueOrFail(claimed, e);
        }
    }

    private void failPermanently(KbDocumentEntity claimed, Exception e) {
        log.warn("文档索引失败（确定性错误，不重试）documentId={}", claimed.getId(), e);
        claimed.setStatus("FAILED");
        claimed.setErrorMessage(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        claimed.setNextRetryAt(null);
        deleteChunksOf(claimed);
        updateGuardedByProcessing(claimed);
    }

    private void requeueOrFail(KbDocumentEntity claimed, Exception e) {
        String reason = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        int retried = claimed.getRetryCount() == null ? 1 : claimed.getRetryCount() + 1;
        if (retried > ingestProps.maxRetries()) {
            log.warn("文档索引失败且重试耗尽 documentId={} retryCount={}", claimed.getId(), retried, e);
            deleteChunksOf(claimed); // 终态 FAILED：当场清掉本次尝试的残留，不留半成品
            claimed.setStatus("FAILED");
            claimed.setErrorMessage(reason);
            claimed.setNextRetryAt(null);
            claimed.setRetryCount(retried);
        } else {
            // 重排队：本次尝试的残留 Chunk 由下次尝试开头的幂等清理负责，这里不重复删
            Duration backoff = ingestProps.retryBackoff().multipliedBy(1L << (retried - 1));
            claimed.setStatus("PENDING");
            claimed.setErrorMessage(reason);
            claimed.setRetryCount(retried);
            claimed.setNextRetryAt(LocalDateTime.now().plus(backoff));
            log.warn("文档索引失败，第 {} 次重排队 documentId={} 退避={}s 原因={}",
                    retried, claimed.getId(), backoff.toSeconds(), reason);
        }
        updateGuardedByProcessing(claimed);
    }

    /** 终态写入以 status=PROCESSING 为前置条件：行若被并发改走（如接管），本次结果不覆盖他人状态 */
    private void updateGuardedByProcessing(KbDocumentEntity doc) {
        int updated = documentMapper.update(doc, new UpdateWrapper<KbDocumentEntity>()
                .eq("id", doc.getId())
                .eq("status", "PROCESSING"));
        if (updated != 1) {
            log.error("任务状态写入未命中（行状态已被并发修改，结果丢弃）documentId={} 期望落成 {}",
                    doc.getId(), doc.getStatus());
        }
    }

    private void deleteChunksOf(KbDocumentEntity doc) {
        chunkMapper.delete(new QueryWrapper<KbChunkEntity>().eq("document_id", doc.getId()));
    }

    private void indexChunk(KbDocumentEntity doc, ChunkPart part) {
        String vectorId = doc.getId() + "#" + part.seq() + "#" + doc.getIndexVersion();

        List<Double> vector = embeddingPort.embed(part.text());

        // 1.先写 Chroma（upsert 幂等：同 vector_id 重复执行是覆盖而非新增）
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

        // 2.再写 MySQL（process 开头已清同文档旧 Chunk，重试不会撞主键）
        chunkMapper.insert(chunk);
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
