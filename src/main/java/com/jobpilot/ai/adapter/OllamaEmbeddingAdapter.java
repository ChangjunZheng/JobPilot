package com.jobpilot.ai.adapter;

import com.jobpilot.ai.EmbeddingPort;
import com.jobpilot.config.RagProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Ollama /api/embed 适配器（POST {model, input:[...]} -> {embeddings:[[...]]}）。
 * Ollama 不可用时 RestClient 抛异常，索引任务由上层标记 FAILED。
 */
@Component
public class OllamaEmbeddingAdapter implements EmbeddingPort {

    private final RestClient restClient;
    private final String model;

    public OllamaEmbeddingAdapter(RagProperties props, ClientHttpRequestFactory requestFactory) {
        this.restClient = RestClient.builder()
                .baseUrl(props.ollamaBaseUrl())
                .requestFactory(requestFactory)
                .build();
        this.model = props.embeddingModel();
    }

    @Override
    public List<Double> embed(String text) {
        Map<String, Object> request = Map.of("model", model, "input", List.of(text));
        EmbeddingResponse response = restClient.post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(EmbeddingResponse.class);
        if (response == null || response.embeddings() == null || response.embeddings().length == 0) {
            throw new IllegalStateException("Ollama 未返回 embedding，model=" + model);
        }
        return Arrays.stream(response.embeddings()[0]).boxed().toList();
    }

    @Override
    public int dimension() {
        return 1024; // bge-m3 固定 1024 维（本机 ollama show 实测）
    }

    record EmbeddingResponse(double[][] embeddings) {
    }
}
