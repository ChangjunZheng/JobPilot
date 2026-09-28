package com.jobpilot.ai;

/** 检索模式（ARCHITECTURE.md §5.3）：向量检索或 Chroma 不可用时的关键词降级 */
public enum SearchMode {
    VECTOR, // 向量检索
    KEYWORD_FALLBACK // 关键词降级
}
