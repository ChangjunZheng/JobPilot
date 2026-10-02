-- I-1 顺带清理：注册同意隐私政策的合规留痕（谁在何时同意了哪个版本）
-- 之前只在注册请求里校验 consent 布尔值、未落库——正式对外开放前必须有留痕。
ALTER TABLE user_account
    ADD COLUMN privacy_version      VARCHAR(32) NULL COMMENT '注册时同意的隐私政策版本（jobpilot.security.privacy-notice-version）',
    ADD COLUMN privacy_consented_at DATETIME(3) NULL COMMENT '同意时间';
