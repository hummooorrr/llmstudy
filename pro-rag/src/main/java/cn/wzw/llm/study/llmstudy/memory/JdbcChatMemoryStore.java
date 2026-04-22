package cn.wzw.llm.study.llmstudy.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 基于 MySQL 的对话记忆持久化实现。
 * <ul>
 *     <li>{@link #add(String, Message)} 写入单条消息</li>
 *     <li>{@link #get(String)} 返回该会话保留窗口内的消息（按时间正序）</li>
 *     <li>{@link #clear(String)} 清空该会话所有消息</li>
 * </ul>
 * <p>
 * 窗口大小由调用方决定（{@code maxMessages} 构造参数）。超出窗口的历史会保留在表里，
 * 但 {@code get()} 只返回最近 N 条，由此实现"滑动窗口 + 全量归档"。
 */
@Slf4j
public class JdbcChatMemoryStore implements ChatMemory {

    private static final String INSERT_SQL =
            "INSERT INTO pro_rag_chat_message (conversation_id, message_type, content, created_at) "
                    + "VALUES (?, ?, ?, ?)";

    private static final String SELECT_RECENT_SQL =
            "SELECT message_type, content FROM ("
                    + "  SELECT message_type, content, id FROM pro_rag_chat_message "
                    + "  WHERE conversation_id = ? ORDER BY id DESC LIMIT ?"
                    + ") t ORDER BY t.id ASC";

    private static final String DELETE_SQL =
            "DELETE FROM pro_rag_chat_message WHERE conversation_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final int maxMessages;

    public JdbcChatMemoryStore(@Qualifier("chatMemoryJdbcTemplate") JdbcTemplate jdbcTemplate,
                               int maxMessages) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxMessages = Math.max(1, maxMessages);
    }

    @Override
    public void add(String conversationId, Message message) {
        if (message == null || conversationId == null) {
            return;
        }
        String type = toType(message.getMessageType());
        if (type == null) {
            return; // TOOL / 未知类型忽略，避免把内部消息塞进历史干扰下一次 prompt
        }
        String content = message.getText();
        try {
            jdbcTemplate.update(INSERT_SQL,
                    conversationId,
                    type,
                    content == null ? "" : content,
                    Timestamp.from(Instant.now()));
        } catch (Exception e) {
            log.warn("写入对话记忆失败 conversationId={} type={} msg={}", conversationId, type, e.getMessage());
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Message message : messages) {
            add(conversationId, message);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        if (conversationId == null) {
            return Collections.emptyList();
        }
        try {
            return jdbcTemplate.query(SELECT_RECENT_SQL,
                    ps -> {
                        ps.setString(1, conversationId);
                        ps.setInt(2, maxMessages);
                    },
                    (rs, rowNum) -> toMessage(rs.getString("message_type"), rs.getString("content"))
            ).stream().filter(m -> m != null).toList();
        } catch (Exception e) {
            log.warn("读取对话记忆失败 conversationId={} msg={}", conversationId, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null) {
            return;
        }
        try {
            jdbcTemplate.update(DELETE_SQL, conversationId);
        } catch (Exception e) {
            log.warn("清空对话记忆失败 conversationId={} msg={}", conversationId, e.getMessage());
        }
    }

    private static String toType(MessageType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case USER -> "USER";
            case ASSISTANT -> "ASSISTANT";
            case SYSTEM -> "SYSTEM";
            default -> null;
        };
    }

    private static Message toMessage(String type, String content) {
        if (type == null) {
            return null;
        }
        String body = content == null ? "" : content;
        return switch (type) {
            case "USER" -> new UserMessage(body);
            case "ASSISTANT" -> new AssistantMessage(body);
            case "SYSTEM" -> new SystemMessage(body);
            default -> null;
        };
    }
}
