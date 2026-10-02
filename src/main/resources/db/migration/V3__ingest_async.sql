-- I-1c 导入异步化：DB 当队列（ARCHITECTURE §4.1，不引入消息队列）
-- 重试状态存行内：worker 崩溃 / 重启后任务状态不丢，接管逻辑（重置僵死 PROCESSING）基于同一行运转。
-- content 落库：异步后索引发生在 worker 线程，原文必须持久化（也是将来 reindex 的事实来源）。
ALTER TABLE kb_document
    ADD COLUMN content        MEDIUMTEXT  NULL COMMENT '导入原文（worker 异步索引的事实来源；reindex 依赖）',
    ADD COLUMN retry_count    INT         NOT NULL DEFAULT 0 COMMENT '已重排队次数；0 = 首次执行',
    ADD COLUMN next_retry_at  DATETIME(3) NULL COMMENT '最早可认领时间；NULL = 立即可认领（失败退避用）',
    ADD KEY idx_kb_document_claim (status, next_retry_at, created_at);
