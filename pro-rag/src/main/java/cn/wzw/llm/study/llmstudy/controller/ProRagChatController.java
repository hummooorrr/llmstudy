package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.config.DomainPromptConfig;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用RAG问答接口（含对话记忆）
 * 支持多轮对话，流式输出；同时提供 SSE 多事件通道用于前端渲染引用卡片。
 */
@RestController
@RequestMapping("/pro-rag")
public class ProRagChatController {

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[\\^c(\\d+)]");

    @Autowired
    private ProRagConfiguration proRagConfiguration;

    @Autowired
    private ProRagRetrievalService proRagRetrievalService;

    @Autowired
    private DomainPromptConfig domainPromptConfig;

    @Autowired
    private ConversationMetaService conversationMetaService;

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
        String normalizedChatId = normalizeChatId(chatId);
        conversationMetaService.touch(normalizedChatId, ConversationScope.CHAT, domain, message);
        GenerationReferenceBundle bundle = proRagRetrievalService.retrieveReferenceBundle(message, null);
        String userMessage = buildUserMessage(domain, message, bundle);

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
        String normalizedChatId = normalizeChatId(chatId);
        conversationMetaService.touch(normalizedChatId, ConversationScope.CHAT, domain, message);
        GenerationReferenceBundle bundle;
        try {
            bundle = proRagRetrievalService.retrieveReferenceBundle(message, null);
        } catch (Exception e) {
            return Flux.just(sseEvent("error", Map.of("message", e.getMessage())));
        }

        List<ReferenceMaterial> references = bundle.referenceMaterials();
        String userMessage = buildUserMessage(domain, message, bundle);

        ChatClient chatChatClient = proRagConfiguration.getChatChatClient();

        // Reactor 的 Flux.concat 不保证 messageFlux 与 doneFlux 运行在同一线程，
        // StringBuilder 非线程安全；改用 StringBuffer 保证写入/读取的 happens-before。
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
            List<String> usedRefIds = extractUsedRefIds(answerBuffer.toString(), references);
            // 流式结束后 touch 一次，同步最新 updated_at / message_count
            conversationMetaService.touch(normalizedChatId, ConversationScope.CHAT, domain, message);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("usedRefIds", usedRefIds);
            payload.put("totalRefs", references.size());
            payload.put("chatId", normalizedChatId);
            return sseEvent("done", payload);
        }).flux();

        return Flux.concat(referencesFlux, messageFlux, doneFlux);
    }

    /**
     * 统一 chatId：如果前端没带 scope 前缀，自动补 chat-，保证这条会话落在 CHAT 作用域下。
     */
    private String normalizeChatId(String chatId) {
        if (!StringUtils.hasText(chatId)) {
            throw new IllegalArgumentException("chatId 不能为空");
        }
        String trimmed = chatId.trim();
        if (trimmed.startsWith("chat-") || trimmed.startsWith("gen-")) {
            return trimmed;
        }
        return "chat-" + trimmed;
    }

    private String buildUserMessage(String domain, String userQuestion, GenerationReferenceBundle bundle) {
        String chatPrompt = domainPromptConfig.getDomain(domain).chatPrompt();
        String documentContent = renderReferences(bundle);
        return String.format(chatPrompt, documentContent, userQuestion);
    }

    /**
     * 把 references 的标题元数据与 contents 里对应同一下标的完整文本拼成 prompt。
     * references 与 contents 由 {@code hybridFusionDetailed} 严格 1:1 对齐，
     * LLM 看到的 [cN] 即前端 refId = cN。
     */
    private String renderReferences(GenerationReferenceBundle bundle) {
        List<ReferenceMaterial> references = bundle.referenceMaterials();
        List<String> contents = bundle.contents();
        if (references == null || references.isEmpty()) {
            return "（暂无可用参考文档）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < references.size(); i++) {
            ReferenceMaterial ref = references.get(i);
            String fullText = i < contents.size() ? contents.get(i) : ref.excerpt();
            sb.append('[').append(ref.refId()).append(']');
            sb.append(' ').append('(').append(ref.filename());
            if (ref.pageNumber() != null) {
                sb.append(" p.").append(ref.pageNumber());
            }
            if (StringUtils.hasText(ref.sectionPath())) {
                sb.append(" / ").append(ref.sectionPath());
            }
            if (StringUtils.hasText(ref.chunkType()) && !"TEXT".equalsIgnoreCase(ref.chunkType())) {
                sb.append(" · ").append(ref.chunkType());
            }
            sb.append(")\n");
            sb.append(fullText == null ? "" : fullText.trim());
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    private List<String> extractUsedRefIds(String answer, List<ReferenceMaterial> references) {
        if (!StringUtils.hasText(answer) || references == null || references.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> used = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            used.add("c" + matcher.group(1));
        }
        List<String> validRefIds = references.stream().map(ReferenceMaterial::refId).toList();
        List<String> result = new ArrayList<>();
        for (String refId : used) {
            if (validRefIds.contains(refId)) {
                result.add(refId);
            }
        }
        return result;
    }

    private ServerSentEvent<Object> sseEvent(String name, Object payload) {
        return ServerSentEvent.builder(payload)
                .event(name)
                .build();
    }
}
