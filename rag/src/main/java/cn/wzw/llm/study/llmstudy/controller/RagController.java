package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.service.DocumentReaderStrategySelector;
import cn.wzw.llm.study.llmstudy.service.EmbeddingService;
import cn.wzw.llm.study.llmstudy.splitter.MarkdownHeaderTextSplitter;
import cn.wzw.llm.study.llmstudy.splitter.OverlapParagraphTextSplitter;
import cn.wzw.llm.study.llmstudy.splitter.SentenceWindowSplitter;
import cn.wzw.llm.study.llmstudy.splitter.WordHeaderTextSplitter;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.zhipuai.ZhiPuAiChatOptions;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG文档处理接口
 * 提供多种文档读取和分片策略，按场景选择不同的管道
 *
 * 数据流: 文件 → Reader(读取) → 清洗 → Splitter(分片) → 向量化入库
 *
 * 各接口适用场景:
 * - /load                  : 通用场景，Reader已分片，直接入库
 * - /overlapSplit          : 通用场景，按固定大小+重叠切分
 * - /markdownHeaderSplit   : Markdown文档，按标题层级建立父子关系
 * - /sentenceWindowSplit   : 短文本/FAQ，按句子切分并附加窗口上下文
 * - /wordHeaderSplit       : Word文档，按标题样式建立父子关系
 */
@RestController
@RequestMapping("/rag")
public class RagController  implements InitializingBean {

    private final DocumentReaderStrategySelector selector;

    @Autowired
    private EmbeddingService embeddingService;

    private ChatClient chatClient;

    @Autowired
    private ChatModel zhiPuAiChatModel;

    @Autowired
    private PgVectorStore vectorStore;

    @Autowired
    public RagController(DocumentReaderStrategySelector selector) {
        this.selector = selector;
    }

    /**
     * 构建来源文件元数据，用于绕过Reader直接构造Document的场景
     */
    private Map<String, Object> sourceMetadata(File file) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("filename", file.getName());
        metadata.put("filePath", file.getAbsolutePath());
        return metadata;
    }

    /**
     * 校验文件路径，无效时抛出 IllegalArgumentException
     */
    private File resolveFile(String path) {
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在或不是有效文件: " + path);
        }
        return file;
    }

    /**
     * 轻量清洗：去除多余空行和首尾空白，保留所有内容信息
     * 不做去重、不删特殊符号、不压缩换行，避免丢失检索关键信息
     */
    private List<Document> clean(List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return documents;
        }

        return documents.stream()
                .map(doc -> {
                    if (doc == null || doc.getText() == null) {
                        return doc;
                    }
                    String text = doc.getText().replaceAll("\n{3,}", "\n\n").trim();
                    return new Document(text, doc.getMetadata());
                })
                .toList();
    }

    /**
     * 通用文档加载
     * Reader内部已按文件类型做了一次分片，清洗后直接返回，不做二次切分
     *
     * @param path 文件绝对路径
     */
    @GetMapping("/load")
    public List<Document> load(@RequestParam("path") String path) {
        try {
            return clean(selector.read(resolveFile(path)));
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重叠段落分片
     * Reader读取后清洗，再按固定大小+重叠字符切分，适合通用检索场景
     *
     * @param path 文件绝对路径
     */
    @GetMapping("/overlapSplit")
    public List<Document> overlapSplit(@RequestParam("path") String path) {
        try {
            List<Document> documents = clean(selector.read(resolveFile(path)));
            OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(400, 100);
            return splitter.apply(documents);
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * Markdown按标题层级分片（父子模式）
     * 直接读原始文本，避免MarkdownDocumentReader预处理导致标题层级丢失
     * 父分片: 高级标题下的完整内容; 子分片: 低级标题下的具体段落
     *
     * @param path 文件绝对路径（.md文件）
     */
    @GetMapping("/markdownHeaderSplit")
    public List<Document> markdownHeaderSplit(@RequestParam("path") String path) {
        try {
            File file = resolveFile(path);
            String rawText = Files.readString(file.toPath());
            Document rawDoc = new Document(rawText, sourceMetadata(file));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("# ", "h1");
            headers.put("## ", "h2");
            headers.put("### ", "h3");

            MarkdownHeaderTextSplitter splitter = new MarkdownHeaderTextSplitter(headers, false, false, true);
            return splitter.apply(List.of(rawDoc));
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 句子窗口分片
     * 按句子切分，每个句子附加前后N句作为窗口上下文
     * 适合短文本、FAQ等无明显层级结构的文档
     *
     * @param path       文件绝对路径
     * @param windowSize 窗口大小，每个句子前后各保留N句，默认2
     */
    @GetMapping("/sentenceWindowSplit")
    public List<Document> sentenceWindowSplit(@RequestParam("path") String path,
                                               @RequestParam(value = "windowSize", defaultValue = "2") int windowSize) {
        try {
            List<Document> documents = clean(selector.read(resolveFile(path)));
            SentenceWindowSplitter splitter = new SentenceWindowSplitter(windowSize);
            return splitter.apply(documents);
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * Word按标题样式分片（父子模式）
     * 传原始文件流给WordHeaderTextSplitter，避免Tika预处理丢失标题样式（Heading1-6）
     * 超过chunkSize的分块会自动做重叠二次切分
     *
     * @param path 文件绝对路径（.doc/.docx文件）
     */
    @GetMapping("/wordHeaderSplit")
    public List<Document> wordHeaderSplit(@RequestParam("path") String path) {
        try {
            File file = resolveFile(path);
            Map<String, Object> metadata = sourceMetadata(file);
            metadata.put("wordInputStream", Files.readAllBytes(file.toPath()));
            Document doc = new Document("", metadata);

            WordHeaderTextSplitter splitter = new WordHeaderTextSplitter(null, false, false, true, 500, 100);
            return splitter.apply(List.of(doc));
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通用文档分片入库
     * 根据文件扩展名自动选择分片策略，分片后直接写入向量库
     *
     * @param path 文件绝对路径
     * @return 处理结果摘要
     */
    @PostMapping("embed")
    public Map<String, Object> embed(@RequestParam("path") String path) {
        File file = resolveFile(path);
        String fileName = file.getName().toLowerCase();

        try {
            List<Document> chunks;

            if (fileName.endsWith(".md")) {
                String rawText = Files.readString(file.toPath());
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("# ", "h1");
                headers.put("## ", "h2");
                headers.put("### ", "h3");
                MarkdownHeaderTextSplitter splitter = new MarkdownHeaderTextSplitter(headers, false, false, true);
                chunks = splitter.apply(List.of(new Document(rawText, sourceMetadata(file))));
            } else if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
                Map<String, Object> metadata = sourceMetadata(file);
                metadata.put("wordInputStream", Files.readAllBytes(file.toPath()));
                WordHeaderTextSplitter splitter = new WordHeaderTextSplitter(null, false, false, true, 500, 100);
                chunks = splitter.apply(List.of(new Document("", metadata)));
            } else {
                // 其他格式：先Reader读取清洗，再做重叠分片
                List<Document> documents = clean(selector.read(file));
                OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(400, 100);
                chunks = splitter.apply(documents);
            }

            embeddingService.embedAndStore(chunks);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("file", file.getName());
            result.put("chunks", chunks.size());
            result.put("status", "success");
            return result;
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }



    @GetMapping("/retrieveAdvisor")
    public String retrieveAdvisor(String query) {
        return chatClient.prompt(query).call().content();
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        // 自定义Prompt模板
        PromptTemplate promptTemplate = new PromptTemplate("""
                请基于以下提供的参考文档内容，回答用户的问题。
                如果参考文档中没有相关信息，请直接说明"没有找到相关信息"，不要编造内容。
                
                参考文档内容:
                {question_answer_context}
                
                用户问题: {query}
                """);

        QuestionAnswerAdvisor questionAnswerAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().similarityThreshold(0.5).topK(5).build())
                .promptTemplate(promptTemplate).build();

        this.chatClient = ChatClient.builder(zhiPuAiChatModel)
                // 实现 Logger 的 Advisor
                .defaultAdvisors(questionAnswerAdvisor)
                // 设置 ChatClient 中 ChatModel 的 Options 参数
                .defaultOptions(
                        ZhiPuAiChatOptions.builder()
                                .build()
                ).build();
    }

}
