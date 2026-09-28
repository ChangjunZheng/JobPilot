package com.jobpilot.ai.adapter;

import com.jobpilot.ai.ChatPort;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI ChatModel 适配器（非流式）。
 * 业务层只依赖 ChatPort；Spring AI 负责 Ollama 协议和响应模型转换，Agent 控制流仍由 JobPilot 自己实现。
 */
@Component
public class OllamaChatAdapter implements ChatPort {

    private final ChatModel chatModel;

    public OllamaChatAdapter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt));
        ChatResponse response = chatModel.call(new Prompt(messages));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Ollama 未返回回答");
        }
        AssistantMessage answer = response.getResult().getOutput();
        if (answer.getText() == null || answer.getText().isBlank()) {
            throw new IllegalStateException("Ollama 未返回有效回答");
        }
        return answer.getText().trim();
    }
}
