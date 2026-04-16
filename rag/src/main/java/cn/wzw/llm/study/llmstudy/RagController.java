package cn.wzw.llm.study.llmstudy;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

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
public class RagController {

    private final DocumentReaderStrategySelector selector;

    @Autowired
    public RagController(DocumentReaderStrategySelector selector) {
        this.selector = selector;
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
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在或不是有效文件: " + path);
        }
        try {
            return clean(selector.read(file));
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
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在或不是有效文件: " + path);
        }
        try {
            List<Document> documents = clean(selector.read(file));
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
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在或不是有效文件: " + path);
        }
        try {
            String rawText = Files.readString(file.toPath());
            Document rawDoc = new Document(rawText);

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
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在或不是有效文件: " + path);
        }
        try {
            List<Document> documents = clean(selector.read(file));
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
        File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在或不是有效文件: " + path);
        }
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("wordInputStream", Files.readAllBytes(file.toPath()));
            Document doc = new Document("", metadata);

            WordHeaderTextSplitter splitter = new WordHeaderTextSplitter(null, false, false, true, 500, 100);
            return splitter.apply(List.of(doc));
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }
}
