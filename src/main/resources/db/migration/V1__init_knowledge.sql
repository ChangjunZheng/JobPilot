-- M-1 RAG 最小闭环：知识库文档与 Chunk 元数据（MySQL 是事实来源，Chroma 只存向量）
CREATE TABLE IF NOT EXISTS kb_document (
    id            VARCHAR(36)  NOT NULL COMMENT '文档 ID（UUID）',
    user_id       VARCHAR(64)  NOT NULL COMMENT '归属用户，day1 即贯穿全链路',
    name          VARCHAR(255) NOT NULL COMMENT '文档展示名（用于引用）',
    doc_type      VARCHAR(32)  NOT NULL COMMENT 'MARKDOWN / PLAIN_TEXT',
    tags          VARCHAR(512) NULL COMMENT '逗号分隔标签，预留过滤',
    status        VARCHAR(32)  NOT NULL COMMENT 'PENDING/PROCESSING/READY/FAILED/DELETED，仅 READY 可检索',
    index_version INT          NOT NULL DEFAULT 1 COMMENT '索引版本，切分策略或 embedding 模型变更时重建',
    chunk_count   INT          NOT NULL DEFAULT 0 COMMENT '成功写入的 Chunk 数',
    error_message VARCHAR(512) NULL COMMENT 'FAILED 时的可重试原因',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_kb_document_user_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '知识库文档';

CREATE TABLE IF NOT EXISTS kb_chunk (
    vector_id     VARCHAR(160) NOT NULL COMMENT 'Chroma 向量 ID：docId#seq#indexVersion，重建索引天然幂等',
    document_id   VARCHAR(36)  NOT NULL COMMENT '所属文档',
    user_id       VARCHAR(64)  NOT NULL COMMENT '冗余归属，降级检索与删除定位用',
    doc_name      VARCHAR(255) NOT NULL COMMENT '冗余文档名，组装引用不依赖 JOIN',
    doc_type      VARCHAR(32)  NOT NULL COMMENT '冗余文档类型，作为检索过滤维度',
    section_path  VARCHAR(512) NULL COMMENT '章节路径，如 H1 > H2；纯文本为 /',
    seq           INT          NOT NULL COMMENT 'Chunk 在文档内的顺序号（从 0 开始）',
    text          TEXT         NOT NULL COMMENT 'Chunk 原文（引用事实来源）',
    char_start    INT          NOT NULL COMMENT '在原文中的起始字符位置（引用定位）',
    char_end      INT          NOT NULL COMMENT '在原文中的结束字符位置（引用定位）',
    index_version INT          NOT NULL DEFAULT 1 COMMENT '与文档索引版本一致',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (vector_id),
    KEY idx_kb_chunk_doc (document_id, seq),
    KEY idx_kb_chunk_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '知识库 Chunk 原文与引用元数据';
