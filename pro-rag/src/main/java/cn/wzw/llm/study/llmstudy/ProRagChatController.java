package cn.wzw.llm.study.llmstudy;

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

    private static final String CHAT_PROMPT =
            "你是银行风控领域的智能助手，专门帮助银行风控从业人员解答工作中遇到的问题。\n"
                    + "请基于以下参考文档内容回答用户的问题。如果参考文档中没有相关信息，请直接说明\"没有找到相关信息\"，不要编造内容。\n\n"
                    + "参考文档：\n"
                    + "%s\n\n"
                    + "用户问题：%s\n";

    /**
     * 通用RAG问答（流式输出）
     *
     * @param message 用户消息
     * @param chatId  会话ID，同一ID保持对话记忆
     */
    @GetMapping("/chat")
    public Flux<String> chat(
            @RequestParam("message") String message,
            @RequestParam("chatId") String chatId,
            HttpServletResponse response
    ) throws Exception {
        response.setCharacterEncoding("UTF-8");

        // 1. 混合检索（问题重写 + 向量检索 + 关键词检索 + RRF 融合 + Rerank 精排）
        List<String> mergedContents = proRagRetrievalService.retrieveReferenceBundle(message, null).contents();

        // 2. 构建 Prompt
        String documentContent = String.join("\n\n=========文档分隔线===========\n\n", mergedContents);
        String userMessage = String.format(CHAT_PROMPT, documentContent, message);

        // 3. 带对话记忆的流式回答
        ChatClient chatChatClient = proRagConfiguration.getChatChatClient();
        return chatChatClient.prompt()
                .user(userMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }
}
