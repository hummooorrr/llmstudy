package cn.wzw.llm.study.llmstudy;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface LangChainMemoryAiService {

    String chatMemory(@MemoryId String memoryId, @UserMessage String userMessage);
}
