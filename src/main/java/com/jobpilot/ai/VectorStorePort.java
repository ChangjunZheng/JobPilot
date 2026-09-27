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

    /**
     * 探活：返回当前集合的信息。
     * 集合不存在时抛 {@link CollectionNotFoundException}（永久性配置错误，调用方应阻断启动）；
     * 向量库不可达时抛其他运行时异常（临时故障，调用方应降级而非中断）。
     * dimension 可能为 null —— 空集合尚未确立维度。
     */
    CollectionInfo collectionInfo();

    record VectorMatch(String id, double score) {
    }

    /** 集合展示信息，供启动自检与维度校验使用 */
    record CollectionInfo(String id, String name, Integer dimension) {
    }
}
