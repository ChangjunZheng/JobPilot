package com.jobpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 最小闭环配置（jobpilot.rag.*）。
 * 端口/适配层只依赖这里的值，业务层不感知 Ollama/Chroma 的存在。
 */
@ConfigurationProperties(prefix = "jobpilot.rag")
public record RagProperties(
        String ollamaBaseUrl,
        String embeddingModel,
        String chatModel,
        String chromaBaseUrl,
        String chromaCollection,
        String chromaCollectionId,
        int chunkSize,
        int chunkOverlap,
        int topK,
        double similarityThreshold,
        int keywordMinHits
) {
}
