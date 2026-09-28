package com.jobpilot.ai.adapter;

import com.jobpilot.ai.EmbeddingPort;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Spring AI EmbeddingModel 适配器。
 * 业务层只依赖 EmbeddingPort；Spring AI 负责 Ollama embedding 协议和 float[] 响应转换。
 */
@Component
public class OllamaEmbeddingAdapter implements EmbeddingPort {

    private final EmbeddingModel embeddingModel;

    public OllamaEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<Double> embed(String text) {
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(text));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Ollama 未返回 embedding");
        }
        return Arrays.stream(response.getResult().getOutput()).boxed().toList();
    }

    @Override
    public int dimension() {
        return embeddingModel.dimensions();
    }
}
