package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.service.LangChainPersistentAiService;
import cn.wzw.llm.study.llmstudy.store.RedisChatMemoryStore;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LangChain4j Redis 持久化对话记忆演示：跨重启保留聊天历史
 */
@RestController
@RequestMapping("/langchain")
public class LangChainPersistentController implements InitializingBean {

    @Autowired
    OpenAiChatModel chatModel;

    @Autowired
    RedisChatMemoryStore redisChatMemoryStore;

    private LangChainPersistentAiService persistentAiService;

    @Override
    public void afterPropertiesSet() throws Exception {
        persistentAiService = AiServices.builder(LangChainPersistentAiService.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .chatMemoryStore(redisChatMemoryStore)
                        .id(memoryId)
                        .maxMessages(20)
                        .build())
                .build();
    }

    @RequestMapping("/persistentChat")
    public String persistentChat(HttpServletResponse response, String msg, String memoryId) {
        response.setCharacterEncoding("UTF-8");
        return persistentAiService.chat(memoryId, msg);
    }
}
