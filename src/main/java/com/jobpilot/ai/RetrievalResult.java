package com.jobpilot.ai;

import java.util.List;

/** 检索结果：degraded=true 时 searchMode 必为 KEYWORD_FALLBACK，必须透传给调用方 */
public record RetrievalResult(
        List<RetrievedChunk> items,
        SearchMode searchMode,
        boolean degraded
) {

    public static RetrievalResult vector(List<RetrievedChunk> items) {
        return new RetrievalResult(items, SearchMode.VECTOR, false);
    }

    public static RetrievalResult keywordFallback(List<RetrievedChunk> items) {
        return new RetrievalResult(items, SearchMode.KEYWORD_FALLBACK, true);
    }
}
