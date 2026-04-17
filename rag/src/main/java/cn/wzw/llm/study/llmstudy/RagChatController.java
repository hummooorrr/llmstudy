package cn.wzw.llm.study.llmstudy;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG对话接口
 * 手动控制 检索 + 重写 + 生成 链路，不依赖 QuestionAnswerAdvisor
 */
@RestController
@RequestMapping("/rag/chat")
public class RagChatController {

    @Autowired
    private QuestionRewriteService questionRewriteService;

    @Autowired
    private VectorStore vectorStore;

    /**
     * 纯 ChatClient，不带 QuestionAnswerAdvisor，避免和手动检索冲突
     */
    private final ChatClient chatClient;

    public RagChatController(@Autowired ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    @GetMapping("/queryRewrite")
    public String chatWithQueryRewrite(@RequestParam("query") String query,
                                       @RequestParam(value = "filename", required = false) String filename) {
        // 1. 问题重写
        List<String> rewriteQuery = questionRewriteService.rewriteQuery(query);

        // 2. 多路检索，用 Set 去重
        Set<Document> similarDocs = new LinkedHashSet<>();
        for (String q : rewriteQuery) {
            SearchRequest.Builder searchBuilder = SearchRequest.builder()
                    .query(q)
                    .topK(5)
                    .similarityThreshold(0.5);

            if (filename != null && !filename.isBlank()) {
                searchBuilder.filterExpression("filename contains '" + filename + "'");
            }

            List<Document> docs = vectorStore.similaritySearch(searchBuilder.build());
            if (docs != null) {
                similarDocs.addAll(docs);
            }
        }

        // 3. 拼接检索文档
        String documentContent = similarDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n=========文档分隔线===========\n\n"));

        // 4. 构建 Prompt 并调用大模型
        PromptTemplate promptTemplate = new PromptTemplate("""
                请基于以下提供的参考文档内容，回答用户的问题。

                参考文档:
                {documents}

                用户问题: {question}
                """);
        Prompt prompt = promptTemplate.create(Map.of("documents", documentContent, "question", query));

        return chatClient.prompt(prompt).call().chatResponse().getResult().getOutput().getText();
    }
}
