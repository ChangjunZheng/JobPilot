-- I-3a 投递记录：Application 字段、状态与按状态/时间范围查询（PRD-FP-7）
-- 带 user_id，因此**不**加入 TENANT_EXEMPT_TABLES、**不**使用 @InterceptorIgnore：
-- 投递记录的读写全在请求线程上，UserContext 已由认证拦截器就位，租户条件自动注入。
--
-- 不加外键（与 kb_document / kb_chunk 一致）：jd_document_id 的归属在写入时用
-- 租户范围内的查询校验——查不到（含他人的文档）即拒绝，比外键更能表达「租户内可见」。

CREATE TABLE IF NOT EXISTS job_application (
    id             VARCHAR(36)   NOT NULL COMMENT '投递记录 ID（UUID）',
    user_id        VARCHAR(64)   NOT NULL COMMENT '租户键；由 TenantLineInnerInterceptor 强制注入',
    company        VARCHAR(255)  NOT NULL COMMENT '公司名称',
    position       VARCHAR(255)  NOT NULL COMMENT '岗位名称',
    status         VARCHAR(32)   NOT NULL COMMENT 'WISHLIST/APPLIED/SCREENING/INTERVIEWING/OFFER/REJECTED/WITHDRAWN',
    applied_at     DATE          NULL COMMENT '投递日期；WISHLIST 尚未投递，可为空。用 DATE 而非 DATETIME：过滤是按天语义，DATETIME 配 <= 会丢最后一天',
    source         VARCHAR(512)  NULL COMMENT '来源/链接（可选）',
    jd_document_id VARCHAR(36)   NULL COMMENT '关联 JD 的 kb_document.id（可选，无外键）',
    notes          VARCHAR(2000) NULL COMMENT '备注',
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_job_application_user_status (user_id, status),
    KEY idx_job_application_user_applied (user_id, applied_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '投递记录';
