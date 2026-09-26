package com.jobpilot.ai;

import java.util.List;
import java.util.Map;

/**
 * 向量库端口。实现负责把 metadata（user_id/doc_type 等）翻译成底层存储的过滤语法。
 * 向量不可用/失败时抛 RuntimeException，由检索服务决定降级策略。
 */
public interface VectorStorePort {

    /** 幂等写入：同 id 覆盖 */
    void upsert(String id, List<Double> vector, Map<String, Object> metadata);

    void delete(String id);

    /** 相似度检索，按分数降序返回至多 topK 条 */
    List<VectorMatch> search(List<Double> queryVector, int topK, Map<String, Object> filters);

    record VectorMatch(String id, double score) {
    }
}
