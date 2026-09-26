package com.jobpilot.ai;

import java.util.List;

/**
 * Embedding 端口（ARCHITECTURE.md §5.4 的极简形态）。
 * M-1 暴力解法：逐条调用，批量/分页留待评测后优化。
 */
public interface EmbeddingPort {

    /** 返回单条文本的 embedding 向量 */
    List<Double> embed(String text);

    /** 向量维度，用于启动期自检与 Chroma collection 对齐 */
    int dimension();
}
