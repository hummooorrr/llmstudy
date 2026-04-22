package cn.wzw.llm.study.llmstudy.memory;

import java.time.Instant;

/**
 * 会话元信息。暴露给前端侧栏的会话列表使用。
 */
public record ConversationInfo(
        String conversationId,
        String scope,
        String title,
        String domain,
        int messageCount,
        Instant createdAt,
        Instant updatedAt
) {
}
