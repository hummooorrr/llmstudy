package cn.wzw.llm.study.llmstudy.config;

import cn.wzw.llm.study.llmstudy.memory.JdbcChatMemoryStore;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ChatClient 配置
 * 为文档生成和通用问答分别创建带"持久化对话记忆"的 ChatClient 实例。
 * 记忆存储到 MySQL，由 {@link JdbcChatMemoryStore} 实现，重启不丢，支持回看和切换旧会话。
 */
@Configuration
public class ProRagConfiguration {

    private final ChatModel chatModel;
    private final ChatMemory chatChatMemory;
    private final ChatMemory generateChatMemory;

    private ChatClient generateChatClient;
    private ChatClient chatChatClient;

    public ProRagConfiguration(
            ChatModel chatModel,
            @Qualifier("chatMemoryJdbcTemplate") JdbcTemplate chatMemoryJdbcTemplate,
            @Value("${pro-rag.chat-memory.chat-window:30}") int chatWindow,
            @Value("${pro-rag.chat-memory.generate-window:30}") int generateWindow) {
        this.chatModel = chatModel;
        this.chatChatMemory = new JdbcChatMemoryStore(chatMemoryJdbcTemplate, chatWindow);
        this.generateChatMemory = new JdbcChatMemoryStore(chatMemoryJdbcTemplate, generateWindow);
        initChatClients();
    }

    public ChatClient getGenerateChatClient() {
        return generateChatClient;
    }

    public ChatClient getChatChatClient() {
        return chatChatClient;
    }

    private void initChatClients() {
        this.generateChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(generateChatMemory).build())
                .build();

        this.chatChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatChatMemory).build())
                .build();
    }
}
