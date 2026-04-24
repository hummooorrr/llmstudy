package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.config.ProRagConfiguration;
import cn.wzw.llm.study.llmstudy.dto.retrieval.GenerationReferenceBundle;
import cn.wzw.llm.study.llmstudy.dto.retrieval.ReferenceMaterial;
import cn.wzw.llm.study.llmstudy.memory.ConversationMetaService;
import cn.wzw.llm.study.llmstudy.memory.ConversationScope;
import cn.wzw.llm.study.llmstudy.service.ProRagRetrievalService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用RAG问答接口（含对话记忆）
 * 支持多轮对话，流式输出；同时提供 SSE 多事件通道用于前端渲染引用卡片。
 */
@RestController
@RequestMapping("/pro-rag")
public class ProRagChatController {

    @Autowired
    private ProRagConfiguration proRagConfiguration;

    @Autowired
    private ProRagRetrievalService proRagRetrievalService;

    @Autowired
    private ConversationMetaService conversationMetaService;

    @Autowired
    private ProRagControllerSupport support;

    /**
     * 兼容接口：旧前端仍可通过原始 /pro-rag/chat 获取纯文本流。
     */
    @GetMapping("/chat")
    public Flux<String> chat(
            @RequestParam("message") String message,
            @RequestParam("chatId") String chatId,
            @RequestParam(value = "domain", defaultValue = "bank_risk") String domain,
            HttpServletResponse response
    ) throws Exception {
        response.setCharacterEncoding("UTF-8");
        String normalizedChatId = support.normalizeChatId(chatId, "chat-");
        conversationMetaService.touch(normalizedChatId, ConversationScope.CHAT, domain, message);
        GenerationReferenceBundle bundle = proRagRetrievalService.retrieveReferenceBundle(message, null);
        String userMessage = support.buildChatUserMessage(domain, message, bundle);

        ChatClient chatChatClient = proRagConfiguration.getChatChatClient();
        return chatChatClient.prompt()
                .user(userMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, normalizedChatId))
                .stream()
                .content()
                .doOnComplete(() -> conversationMetaService.touch(normalizedChatId, ConversationScope.CHAT, domain, message));
    }

    /**
     * SSE 三类事件：references（首帧引用卡片）/ message（delta）/ done（usedRefIds）
     */
    @GetMapping(value = "/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> chatSse(
            @RequestParam("message") String message,
            @RequestParam("chatId") String chatId,
            @RequestParam(value = "domain", defaultValue = "bank_risk") String domain
    ) {
        String normalizedChatId = support.normalizeChatId(chatId, "chat-");
        conversationMetaService.touch(normalizedChatId, ConversationScope.CHAT, domain, message);
        GenerationReferenceBundle bundle;
        try {
            bundle = proRagRetrievalService.retrieveReferenceBundle(message, null);
        } catch (Exception e) {
            return Flux.just(sseEvent("error", Map.of("message", e.getMessage())));
        }

        List<ReferenceMaterial> references = bundle.referenceMaterials();
        String userMessage = support.buildChatUserMessage(domain, message, bundle);

        ChatClient chatChatClient = proRagConfiguration.getChatChatClient();

        StringBuffer answerBuffer = new StringBuffer();

        Flux<ServerSentEvent<Object>> messageFlux = chatChatClient.prompt()
                .user(userMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, normalizedChatId))
                .stream()
                .content()
                .map(delta -> {
                    answerBuffer.append(delta);
                    return sseEvent("message", Map.of("delta", delta));
                });

        Flux<ServerSentEvent<Object>> referencesFlux = Flux.just(
                sseEvent("references", Map.of("items", references, "chatId", normalizedChatId))
        );

        Flux<ServerSentEvent<Object>> doneFlux = Mono.fromSupplier(() -> {
            List<String> usedRefIds = support.extractUsedRefIds(answerBuffer.toString(), references);
            conversationMetaService.touch(normalizedChatId, ConversationScope.CHAT, domain, message);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("usedRefIds", usedRefIds);
            payload.put("totalRefs", references.size());
            payload.put("chatId", normalizedChatId);
            return sseEvent("done", payload);
        }).flux();

        return Flux.concat(referencesFlux, messageFlux, doneFlux);
    }

    private ServerSentEvent<Object> sseEvent(String name, Object payload) {
        return ServerSentEvent.builder(payload)
                .event(name)
                .build();
    }
}
