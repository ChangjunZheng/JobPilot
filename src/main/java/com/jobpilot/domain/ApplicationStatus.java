package com.jobpilot.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * 投递状态（PRD-FP-7）。
 * <p>
 * <b>这是边界类型，不是持久化类型</b>：{@link ApplicationEntity#getStatus()} 仍是 {@code String}，
 * 与项目所有既有实体一致（{@code KbDocumentEntity} / {@code AgentApprovalDraftEntity}）。
 * 枚举负责两件事——写入时的校验与读取统计时的枚举。
 * <p>
 * <b>未知值显式拒绝，不静默降级</b>（ARCHITECTURE §14.3 ③ 的原则）。
 */
public enum ApplicationStatus {
    /** 想投、还没投 */
    WISHLIST,
    /** 已投递 */
    APPLIED,
    /** 筛选中 */
    SCREENING,
    /** 面试中 */
    INTERVIEWING,
    /** 已发 offer */
    OFFER,
    /** 被拒 */
    REJECTED,
    /** 我撤回了 */
    WITHDRAWN;

    /**
     * 解析并校验状态；未知值抛 {@link IllegalArgumentException}，消息里列出允许值。
     * <p>
     * 抛这个异常而非 {@code ApiException}：{@code GlobalExceptionHandler} 已把它映射成 400
     * （与 {@code DocumentIngestService.validate} 同一约定），无需新增错误码；
     * 工具侧则捕获它转成结构化的 {@code INVALID_ARGUMENTS} 回填给模型。
     */
    public static ApplicationStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("投递状态不能为空");
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "未知投递状态：" + raw + "，允许值：" + Arrays.toString(values()));
        }
    }
}
