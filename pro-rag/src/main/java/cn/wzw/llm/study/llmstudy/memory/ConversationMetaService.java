package cn.wzw.llm.study.llmstudy.memory;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话元信息服务：负责管理 {@code pro_rag_conversation} 和 {@code pro_rag_chat_message} 两张表。
 * <ul>
 *   <li>启动时自动建表</li>
 *   <li>每次对话/生成结束后 touch 会话（更新 updated_at、自动生成标题、累计 message_count）</li>
 *   <li>提供会话列表、消息回溯、标题重命名、删除等能力</li>
 * </ul>
 */
@Service
@Slf4j
public class ConversationMetaService {

    /** 会话标题字符数上限（首次出现的 user 消息截断得到）。 */
    private static final int TITLE_MAX_LEN = 40;

    private final JdbcTemplate jdbcTemplate;
    private final boolean initializeSchema;

    public ConversationMetaService(
            @Qualifier("chatMemoryJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Value("${pro-rag.chat-memory.initialize-schema:true}") boolean initializeSchema) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializeSchema = initializeSchema;
    }

    @PostConstruct
    public void initSchema() {
        if (!initializeSchema) {
            return;
        }
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS pro_rag_chat_message (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    conversation_id VARCHAR(64) NOT NULL,
                    message_type VARCHAR(16) NOT NULL,
                    content MEDIUMTEXT NOT NULL,
                    created_at DATETIME NOT NULL,
                    KEY idx_conv_id (conversation_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS pro_rag_conversation (
                    conversation_id VARCHAR(64) NOT NULL PRIMARY KEY,
                    scope VARCHAR(16) NOT NULL,
                    title VARCHAR(255) DEFAULT NULL,
                    domain VARCHAR(32) DEFAULT NULL,
                    message_count INT NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL,
                    updated_at DATETIME NOT NULL,
                    KEY idx_scope_updated (scope, updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
        log.info("对话记忆 MySQL 建表完成");
    }

    /**
     * 在一次对话/生成完成后调用：
     * <ul>
     *   <li>第一次出现则 INSERT，否则 UPDATE updated_at / message_count</li>
     *   <li>如果 title 还为空，用当前 user 消息生成一个标题</li>
     * </ul>
     */
    public void touch(String conversationId, ConversationScope scope, String domain, String userMessage) {
        if (!StringUtils.hasText(conversationId) || scope == null) {
            return;
        }
        Instant now = Instant.now();
        int actualCount = countMessages(conversationId);
        try {
            int updated = jdbcTemplate.update("""
                    UPDATE pro_rag_conversation
                    SET updated_at = ?,
                        domain = COALESCE(?, domain),
                        message_count = ?,
                        title = CASE WHEN (title IS NULL OR title = '') AND ? IS NOT NULL AND ? <> '' THEN ? ELSE title END
                    WHERE conversation_id = ?
                    """,
                    Timestamp.from(now),
                    domain,
                    actualCount,
                    userMessage, userMessage, buildTitle(userMessage),
                    conversationId);
            if (updated == 0) {
                jdbcTemplate.update("""
                        INSERT INTO pro_rag_conversation
                        (conversation_id, scope, title, domain, message_count, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        conversationId,
                        scope.name(),
                        buildTitle(userMessage),
                        domain,
                        actualCount,
                        Timestamp.from(now),
                        Timestamp.from(now));
            }
        } catch (Exception e) {
            log.warn("更新会话元信息失败 conversationId={} msg={}", conversationId, e.getMessage());
        }
    }

    public int countMessages(String conversationId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pro_rag_chat_message WHERE conversation_id = ?",
                    Integer.class, conversationId);
            return count == null ? 0 : count;
        } catch (Exception e) {
            return 0;
        }
    }

    public List<ConversationInfo> listConversations(ConversationScope scope, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        try {
            return jdbcTemplate.query("""
                    SELECT conversation_id, scope, title, domain, message_count, created_at, updated_at
                    FROM pro_rag_conversation
                    WHERE scope = ?
                    ORDER BY updated_at DESC
                    LIMIT ?
                    """,
                    ps -> {
                        ps.setString(1, scope.name());
                        ps.setInt(2, safeLimit);
                    },
                    (rs, rowNum) -> new ConversationInfo(
                            rs.getString("conversation_id"),
                            rs.getString("scope"),
                            rs.getString("title"),
                            rs.getString("domain"),
                            rs.getInt("message_count"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant()
                    )
            );
        } catch (Exception e) {
            log.warn("列出会话失败 scope={} msg={}", scope, e.getMessage());
            return List.of();
        }
    }

    public Optional<ConversationInfo> findConversation(String conversationId) {
        try {
            List<ConversationInfo> list = jdbcTemplate.query("""
                    SELECT conversation_id, scope, title, domain, message_count, created_at, updated_at
                    FROM pro_rag_conversation
                    WHERE conversation_id = ?
                    """,
                    ps -> ps.setString(1, conversationId),
                    (rs, rowNum) -> new ConversationInfo(
                            rs.getString("conversation_id"),
                            rs.getString("scope"),
                            rs.getString("title"),
                            rs.getString("domain"),
                            rs.getInt("message_count"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant()
                    )
            );
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public List<ConversationMessageView> listMessages(String conversationId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        try {
            return jdbcTemplate.query("""
                    SELECT id, message_type, content, created_at
                    FROM pro_rag_chat_message
                    WHERE conversation_id = ?
                    ORDER BY id ASC
                    LIMIT ?
                    """,
                    ps -> {
                        ps.setString(1, conversationId);
                        ps.setInt(2, safeLimit);
                    },
                    (rs, rowNum) -> new ConversationMessageView(
                            rs.getLong("id"),
                            rs.getString("message_type"),
                            rs.getString("content"),
                            rs.getTimestamp("created_at").toInstant()
                    )
            );
        } catch (Exception e) {
            log.warn("加载会话消息失败 conversationId={} msg={}", conversationId, e.getMessage());
            return List.of();
        }
    }

    public void renameConversation(String conversationId, String newTitle) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(newTitle)) {
            return;
        }
        String safe = newTitle.trim();
        if (safe.length() > 200) {
            safe = safe.substring(0, 200);
        }
        jdbcTemplate.update("UPDATE pro_rag_conversation SET title = ?, updated_at = ? WHERE conversation_id = ?",
                safe, Timestamp.from(Instant.now()), conversationId);
    }

    public void deleteConversation(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        jdbcTemplate.update("DELETE FROM pro_rag_chat_message WHERE conversation_id = ?", conversationId);
        jdbcTemplate.update("DELETE FROM pro_rag_conversation WHERE conversation_id = ?", conversationId);
    }

    private String buildTitle(String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return null;
        }
        String normalized = userMessage.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= TITLE_MAX_LEN) {
            return normalized;
        }
        return normalized.substring(0, TITLE_MAX_LEN) + "…";
    }
}
