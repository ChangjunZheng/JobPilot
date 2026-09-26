package com.jobpilot.domain;

/**
 * 文档索引状态机（ARCHITECTURE.md §7.3）。
 * 仅 READY 参与检索；PENDING/PROCESSING/FAILED/DELETED 一律排除。
 */
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED,
    DELETED
}
