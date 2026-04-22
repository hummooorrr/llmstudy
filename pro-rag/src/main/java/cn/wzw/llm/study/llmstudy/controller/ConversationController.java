package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.memory.ConversationInfo;
import cn.wzw.llm.study.llmstudy.memory.ConversationMessageView;
import cn.wzw.llm.study.llmstudy.memory.ConversationMetaService;
import cn.wzw.llm.study.llmstudy.memory.ConversationScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 会话管理接口：前端侧栏列出历史会话、点击某条回溯消息、删除/重命名等。
 */
@RestController
@RequestMapping("/pro-rag/conversations")
public class ConversationController {

    @Autowired
    private ConversationMetaService conversationMetaService;

    @Value("${pro-rag.chat-memory.list-limit:200}")
    private int defaultListLimit;

    /**
     * 列出某个作用域下的会话（按更新时间倒序）。
     *
     * @param scope chat 或 generate（默认 chat）
     * @param limit 最多返回多少条（默认 200，上限 500）
     */
    @GetMapping
    public List<ConversationInfo> list(
            @RequestParam(value = "scope", defaultValue = "chat") String scope,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int actualLimit = limit == null || limit <= 0 ? defaultListLimit : limit;
        return conversationMetaService.listConversations(ConversationScope.from(scope), actualLimit);
    }

    /**
     * 查看某个会话的历史消息（按时间正序），用于"点击历史会话进入时回填聊天记录"。
     */
    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<Map<String, Object>> messages(
            @PathVariable("conversationId") String conversationId,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        int actualLimit = limit == null || limit <= 0 ? 500 : limit;
        ConversationInfo meta = conversationMetaService.findConversation(conversationId).orElse(null);
        if (meta == null) {
            return ResponseEntity.notFound().build();
        }
        List<ConversationMessageView> messages = conversationMetaService.listMessages(conversationId, actualLimit);
        return ResponseEntity.ok(Map.of(
                "conversation", meta,
                "messages", messages
        ));
    }

    /**
     * 重命名会话标题。
     */
    @PostMapping("/{conversationId}/rename")
    public Map<String, Object> rename(
            @PathVariable("conversationId") String conversationId,
            @RequestParam("title") String title
    ) {
        conversationMetaService.renameConversation(conversationId, title);
        return Map.of("status", "ok");
    }

    /**
     * 删除会话（消息 + 元信息）。
     */
    @DeleteMapping("/{conversationId}")
    public Map<String, Object> delete(@PathVariable("conversationId") String conversationId) {
        conversationMetaService.deleteConversation(conversationId);
        return Map.of("status", "ok");
    }
}
