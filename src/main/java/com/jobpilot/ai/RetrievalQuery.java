package com.jobpilot.ai;

/**
 * 检索请求（ARCHITECTURE.md §5.3 RetrievalQuery）。
 * topK <= 0 时由服务层回落默认值。
 */
public record RetrievalQuery(
        String userId,
        String text,
        int topK,
        String docType
) {
}
