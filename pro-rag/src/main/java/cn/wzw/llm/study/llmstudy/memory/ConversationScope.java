package cn.wzw.llm.study.llmstudy.memory;

import org.springframework.util.StringUtils;

/**
 * 会话作用域：把 RAG 对话和文档生成的 chatId 物理分开，避免两边互相干扰。
 * 存在数据库 {@code pro_rag_conversation.scope} 字段里。
 */
public enum ConversationScope {
    CHAT,
    GENERATE;

    public String prefix() {
        return this == CHAT ? "chat-" : "gen-";
    }

    /**
     * 判断给定 conversationId 是否属于本 scope。不带前缀时按"旧数据兼容"处理，
     * 返回 true，交由调用方自行决定是否采纳。
     */
    public boolean matches(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        return conversationId.startsWith(prefix());
    }

    public static ConversationScope from(String value) {
        if (!StringUtils.hasText(value)) {
            return CHAT;
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "GENERATE", "GEN" -> GENERATE;
            default -> CHAT;
        };
    }

    public static ConversationScope fromConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return CHAT;
        }
        if (conversationId.startsWith("gen-")) {
            return GENERATE;
        }
        return CHAT;
    }
}
