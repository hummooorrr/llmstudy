package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import cn.wzw.llm.study.llmstudy.service.ElasticSearchService;
import cn.wzw.llm.study.llmstudy.service.EmbeddingService;
import cn.wzw.llm.study.llmstudy.service.QuestionRewriteService;
import cn.wzw.llm.study.llmstudy.util.RerankUtil;
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

import static cn.wzw.llm.study.llmstudy.util.RerankUtil.rrfFusion;

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

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ElasticSearchService elasticSearchService;

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


    @GetMapping("/hybridchat")
    public String hybridchat(@RequestParam("query") String query) throws Exception {
        // 1. 向量检索获取相似文档
        List<Document> vectorDocs = embeddingService.similarSearch(query);


        // 2. ES 关键词检索
        List<EsDocumentChunk> keywordDocs = elasticSearchService.searchByKeyword(query, 5, true);


        // 3. 根据 id 去重并合并文档
        Map<String, String> idToContent = new LinkedHashMap<>();

        // 向量检索文档
        for (Document doc : vectorDocs) {
            idToContent.putIfAbsent(doc.getId(), doc.getText());
        }

        // ES 关键词检索文档
        for (EsDocumentChunk doc : keywordDocs) {
            idToContent.putIfAbsent(doc.getId(), doc.getContent());
        }

        List<String> mergedContents = rrfFusion(vectorDocs, keywordDocs, 5);

//        List<String> mergedContents = new ArrayList<>(idToContent.values());
//        log.info("共检索到 {} 个相关文档块（向量 + 关键词融合）。", mergedContents.size());

        // 4. 构建提示词模板
        String promptTemplate = """
            请基于以下提供的参考文档内容，回答用户的问题。
            如果参考文档中没有相关信息，请直接说明"没有找到相关信息"，不要编造内容。
            如果有了参考文档内容，请务必尽量回答问题。有可能用户的输入比较随意，你可以先尝试回答用户的问题，猜测他的实际需求，先给出回复，你需要尽量去贴合用户的问题需求。
                            
            参考文档:
            {documents}
                            
            用户问题: {question}
                           
            """;

        // 5. 拼接文档内容
        String documentContent = String.join("\n\n=========文档分隔线===========\n\n", mergedContents);

        // 6. 填充模板参数
        PromptTemplate prompt = new PromptTemplate(promptTemplate);
        Prompt realPrompt = prompt.create(Map.of("documents", documentContent, "question", query));

        // 7. 调用大模型生成回答
        String text = chatClient.prompt(realPrompt).call().chatResponse().getResult().getOutput().getText();

        return text;
    }
}
