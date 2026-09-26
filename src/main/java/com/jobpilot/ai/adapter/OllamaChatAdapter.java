package com.jobpilot.ai.adapter;

import com.jobpilot.ai.ChatPort;
import com.jobpilot.config.RagProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Ollama /api/chat 适配器（非流式）。
 * M-1 用本地模型即可跑通闭环；DeepSeek 等远端 provider 归 M-2 LLM 配置统一管理。
 */
@Component
public class OllamaChatAdapter implements ChatPort {

    private final RestClient restClient;
    private final String model;

    public OllamaChatAdapter(RagProperties props, ClientHttpRequestFactory requestFactory) {
        this.restClient = RestClient.builder()
                .baseUrl(props.ollamaBaseUrl())
                .requestFactory(requestFactory)
                .build();
        this.model = props.chatModel();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        Map<String, Object> request = Map.of(
                "model", model,
                "stream", false,
                "think", false, // qwen3 系列关闭思考链，只取最终回答
                "options", Map.of("temperature", 0.2),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        ChatResponse response = restClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatResponse.class);
        if (response == null || response.message() == null || response.message().content() == null) {
            throw new IllegalStateException("Ollama 未返回回答，model=" + model);
        }
        return response.message().content().trim();
    }

    record ChatResponse(ChatMessage message) {
        record ChatMessage(String role, String content) {
        }
    }
}
