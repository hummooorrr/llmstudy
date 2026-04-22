package cn.wzw.llm.study.llmstudy.memory;

import java.time.Instant;

/**
 * 会话消息视图（给前端回溯历史用）。
 */
public record ConversationMessageView(
        long id,
        String messageType,
        String content,
        Instant createdAt
) {
}
