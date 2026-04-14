package cn.wzw.llm.study.llmstudy;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

public interface LangChainPersistentAiService {

    String chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
