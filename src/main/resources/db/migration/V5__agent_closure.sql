-- I-2 Agent 最小闭环：run trace 与 HITL 审批草稿
-- 三张表都带 user_id，因此**不**加入 TENANT_EXEMPT_TABLES，**不**使用 @InterceptorIgnore：
-- 全部读写都发生在请求线程上，UserContext 已由认证拦截器就位，租户条件照常自动注入。
--
-- trace 天然不进检索：检索只读 kb_document + kb_chunk，trace 是独立的两张表，
-- 不存在被召回的路径——这是结构保证，不是靠过滤条件。

CREATE TABLE IF NOT EXISTS agent_trace (
    id              VARCHAR(36)  NOT NULL COMMENT 'trace ID（UUID）；一次 run 一行',
    user_id         VARCHAR(64)  NOT NULL COMMENT '租户键；由 TenantLineInnerInterceptor 强制注入',
    conversation_id VARCHAR(36)  NOT NULL COMMENT '会话 ID；I-2 仅作关联标识，不跨 run 恢复上下文',
    started_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'run 开始时间',
    ended_at        DATETIME(3)  NULL COMMENT 'run 结束时间；NULL = 尚未收尾（异常退出或进程被杀）',
    duration_ms     BIGINT       NULL COMMENT '总耗时（毫秒）；写 ended_at 时一并计算',
    status          VARCHAR(32)  NOT NULL COMMENT 'OK / ERROR / BUDGET_EXHAUSTED / PENDING_APPROVAL',
    finish_reason   VARCHAR(32)  NOT NULL COMMENT 'STOP / TOOL_CALLS / BUDGET_EXHAUSTED / ERROR',
    request_id      VARCHAR(64)  NULL COMMENT 'RequestIdFilter 写入 MDC 的 requestId，用于与日志对账',
    PRIMARY KEY (id),
    KEY idx_agent_trace_user_started (user_id, started_at),
    KEY idx_agent_trace_conversation (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'Agent run 追踪汇总';

CREATE TABLE IF NOT EXISTS agent_trace_step (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增主键；一次 run 的步数多于一次 run',
    trace_id        VARCHAR(36)   NOT NULL COMMENT '所属 trace',
    user_id         VARCHAR(64)   NOT NULL COMMENT '冗余租户键；便于按租户直查且仍受拦截器保护',
    iteration       INT           NOT NULL COMMENT '第几轮（从 1 开始，上限 jobpilot.agent.max-iterations）',
    kind            VARCHAR(16)   NOT NULL COMMENT 'model / tool',
    name            VARCHAR(128)  NOT NULL COMMENT '模型名或工具名',
    status          VARCHAR(32)   NOT NULL COMMENT 'ok / failed / pending_approval（工具步）',
    duration_ms     BIGINT        NOT NULL DEFAULT 0 COMMENT '本步耗时（毫秒）',
    summary         VARCHAR(1024) NULL COMMENT '脱敏摘要；不写入模型或工具的完整原文',
    arguments_json  VARCHAR(2048) NULL COMMENT '工具入参摘要（脱敏后，超长截断）',
    error_code      VARCHAR(64)   NULL COMMENT '失败归因：TOOL_FAILED / TIMEOUT / INVALID_ARGUMENTS / UNKNOWN_TOOL 等',
    started_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本步开始时间',
    PRIMARY KEY (id),
    KEY idx_agent_trace_step_trace (trace_id, iteration),
    KEY idx_agent_trace_step_user (user_id, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'Agent run 每步追踪';

-- 幂等的两道防线（ARCHITECTURE §4.4）：
--   ① 并发：approve/reject 在事务内 SELECT ... FOR UPDATE 锁行，后到者发现状态已非 PENDING 就不重复执行；
--   ② 重复落库：下面这个唯一键，模型在一次 run 内重复请求同一写入时抛 DuplicateKeyException，
--      工具捕获后按 user_id 重读已有草稿并返回同一个 draftId。
CREATE TABLE IF NOT EXISTS agent_approval_draft (
    id              VARCHAR(36)   NOT NULL COMMENT '草稿 ID（UUID）',
    user_id         VARCHAR(64)   NOT NULL COMMENT '归属租户；审批者必须与之一致，否则按「不存在」处理',
    trace_id        VARCHAR(36)   NOT NULL COMMENT '产生该草稿的 trace，便于回放',
    conversation_id VARCHAR(36)   NOT NULL COMMENT '所属会话',
    tool_name       VARCHAR(64)   NOT NULL COMMENT '请求写入的工具名',
    payload_json    JSON          NOT NULL COMMENT '待执行的写入载荷；审批通过后才被消费',
    status          VARCHAR(32)   NOT NULL COMMENT 'PENDING / APPROVED / REJECTED（EXPIRED 为 PRD 预留，本期不实现过期）',
    decided_at      DATETIME(3)   NULL COMMENT '审批落定时间',
    decided_by      VARCHAR(64)   NULL COMMENT '审批者 user_id；不变量：恒等于 user_id',
    idempotency_key VARCHAR(64)   NOT NULL COMMENT '幂等键：sha256(traceId|toolName|conversationId|规范化载荷)',
    result_ref      VARCHAR(64)   NULL COMMENT '审批通过并执行后产生的资源 ID（如新建文档 ID）',
    executed_at     DATETIME(3)   NULL COMMENT '副作用实际执行时间；与 status=APPROVED 同时写入',
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_approval_idempotency (user_id, idempotency_key) COMMENT '同一用户同幂等键只允许一条草稿',
    KEY idx_agent_approval_user_status (user_id, status),
    KEY idx_agent_approval_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT 'Agent HITL 审批草稿';
