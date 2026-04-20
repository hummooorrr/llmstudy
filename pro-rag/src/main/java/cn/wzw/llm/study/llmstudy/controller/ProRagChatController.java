package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.config.DomainPromptConfig;
import cn.wzw.llm.study.llmstudy.config.ProRagConfiguration;
import cn.wzw.llm.study.llmstudy.service.ProRagRetrievalService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 通用RAG问答接口（含对话记忆）
 * 支持多轮对话，流式输出
 */
@RestController
@RequestMapping("/pro-rag")
public class ProRagChatController {

    @Autowired
    private ProRagConfiguration proRagConfiguration;

    @Autowired
    private ProRagRetrievalService proRagRetrievalService;

    @Autowired
    private DomainPromptConfig domainPromptConfig;

    /**
     * 通用RAG问答（流式输出）
     *
     * @param message 用户消息
     * @param chatId  会话ID，同一ID保持对话记忆
     * @param domain  领域标签，默认 bank_risk
     */
    @GetMapping("/chat")
    public Flux<String> chat(
            @RequestParam("message") String message,
            @RequestParam("chatId") String chatId,
            @RequestParam(value = "domain", defaultValue = "bank_risk") String domain,
            HttpServletResponse response
    ) throws Exception {
        response.setCharacterEncoding("UTF-8");

        // 1. 混合检索（向量检索 + 关键词检索 + RRF 融合 + Rerank 精排）
        List<String> mergedContents = proRagRetrievalService.retrieveReferenceBundle(message, null).contents();

        // 2. 根据领域构建 Prompt
        String chatPrompt = domainPromptConfig.getDomain(domain).chatPrompt();
        String documentContent = String.join("\n\n=========文档分隔线===========\n\n", mergedContents);
        String userMessage = String.format(chatPrompt, documentContent, message);

        // 3. 带对话记忆的流式回答
        ChatClient chatChatClient = proRagConfiguration.getChatChatClient();
        return chatChatClient.prompt()
                .user(userMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }
}
