package com.jobpilot.ai;

/**
 * 探活失败：配置的集合在向量库中不存在。
 * <p>
 * 区别于"向量库不可达"——这是<b>永久性</b>配置错误，不会自愈，调用方应据此阻断启动，
 * 而不是像临时故障那样降级继续（见 ARCHITECTURE.md §8 的降级策略）。
 */
public class CollectionNotFoundException extends RuntimeException {

    public CollectionNotFoundException(String message) {
        super(message);
    }
}
