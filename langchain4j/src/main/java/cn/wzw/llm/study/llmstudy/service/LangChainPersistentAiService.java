package cn.wzw.llm.study.llmstudy.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/**
 * LangChain4j 带持久化对话记忆的 AI 服务接口（需配合 RedisChatMemoryStore）
 */
public interface LangChainPersistentAiService {

    String chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
