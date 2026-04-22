-- pro-rag 对话记忆 / 会话元信息
-- 应用启动时也会通过 ConversationMetaService#initSchema 自动建表，
-- 这里作为"容器首次启动即预建"的保底手段。

CREATE DATABASE IF NOT EXISTS pro_rag
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE pro_rag;

CREATE TABLE IF NOT EXISTS pro_rag_chat_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    message_type VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    created_at DATETIME NOT NULL,
    KEY idx_conv_id (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS pro_rag_conversation (
    conversation_id VARCHAR(64) NOT NULL PRIMARY KEY,
    scope VARCHAR(16) NOT NULL,
    title VARCHAR(255) DEFAULT NULL,
    domain VARCHAR(32) DEFAULT NULL,
    message_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_scope_updated (scope, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
