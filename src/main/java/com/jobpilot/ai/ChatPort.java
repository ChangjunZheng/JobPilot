package com.jobpilot.ai;

/** 对话生成端口（M-1 只需要单轮上下文问答，不做工具调用） */
public interface ChatPort {

    String complete(String systemPrompt, String userPrompt);
}
