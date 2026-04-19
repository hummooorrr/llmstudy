package cn.wzw.llm.study.llmstudy;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 配置
 * 为文档生成和通用问答分别创建带对话记忆的 ChatClient 实例
 */
@Configuration
public class ProRagConfiguration {

    @Autowired
    private ChatModel chatModel;

    /**
     * 文档生成用 ChatClient，记忆窗口20条
     */
    private ChatClient generateChatClient;

    /**
     * 通用问答用 ChatClient，记忆窗口10条
     */
    private ChatClient chatChatClient;

    public ChatClient getGenerateChatClient() {
        return generateChatClient;
    }

    public ChatClient getChatChatClient() {
        return chatChatClient;
    }

    /**
     * 初始化 ChatClient 实例
     */
    @Autowired
    public void initChatClients() {
        // 文档生成：更大的记忆窗口，保留更多轮次
        ChatMemory generateMemory = MessageWindowChatMemory.builder().maxMessages(20).build();
        this.generateChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(generateMemory).build())
                .build();

        // 通用问答：标准记忆窗口
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(10).build();
        this.chatChatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
