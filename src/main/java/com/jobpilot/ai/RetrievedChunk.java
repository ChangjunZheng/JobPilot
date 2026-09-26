package com.jobpilot.ai;

/** 命中的 Chunk：文本来自 MySQL（事实来源），score 为余弦相似度（降级模式为 0） */
public record RetrievedChunk(
        String documentId,
        String chunkId,
        String text,
        double score,
        Citation citation
) {
}
