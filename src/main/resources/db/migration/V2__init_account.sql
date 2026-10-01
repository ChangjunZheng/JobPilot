-- I-1 账号体系：账号主体与登录凭证（租户 = 一个自然人用户，暂不引入组织层级）
CREATE TABLE IF NOT EXISTS user_account (
    id            VARCHAR(36)  NOT NULL COMMENT '账号 ID（UUID，同时是租户键：业务表的 user_id 指向这里）',
    status        VARCHAR(32)  NOT NULL COMMENT 'ACTIVE / DISABLED，DISABLED 账号不得登录',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '用户账号';

-- 凭证与账号分离：唯一键是 (provider, identifier) 而非 email。
-- 这样新增手机号 / 第三方登录只需插一行，不必改主键语义或做数据迁移（ARCHITECTURE §14.2）。
CREATE TABLE IF NOT EXISTS user_credential (
    id            VARCHAR(36)  NOT NULL COMMENT '凭证 ID（UUID）',
    user_id       VARCHAR(36)  NOT NULL COMMENT '归属账号',
    provider      VARCHAR(32)  NOT NULL COMMENT 'EMAIL / PHONE / GITHUB 等，本期仅 EMAIL',
    identifier    VARCHAR(255) NOT NULL COMMENT '登录标识（EMAIL 时为邮箱）。同一 provider 下唯一',
    secret_hash   VARCHAR(100) NOT NULL COMMENT '加盐哈希，绝不落明文；第三方登录时可为随机占位值',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_credential_provider_identifier (provider, identifier),
    KEY idx_user_credential_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT '登录凭证';
