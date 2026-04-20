package cn.wzw.llm.study.llmstudy.splitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Markdown文档分割器，基于标题层级进行文档分段
 * 支持保留元数据、父子分段关系等高级特性
 *
 * @author andyflury （https://github.com/langchain4j/langchain4j/issues/574 ）
 * @author Hollis, 增加对父子分段的支持
 */
public class MarkdownHeaderTextSplitter extends TextSplitter {

    private static final Logger log = LoggerFactory.getLogger(MarkdownHeaderTextSplitter.class);

    /** 需要分割的标题列表，按标题标记长度倒序排列 */
    private List<Map.Entry<String, String>> headersToSplitOn;

    /** 是否按行返回结果 */
    private boolean returnEachLine;

    /** 是否剥离标题行本身 */
    private boolean stripHeaders;

    /** 是否启用父子分段模式 */
    private boolean parentChildModel;

    /**
     * 构造函数
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine 是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders 是否在结果中移除标题行
     * @param parentChildModel 是否启用父子分段模式，启用后会在元数据中添加parentChunkId和childChunkIds
     */
    public MarkdownHeaderTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine, boolean stripHeaders, boolean parentChildModel) {
        // 按标题标记长度倒序排列，确保优先匹配更长的标记（如"###"优先于"##"）
        this.headersToSplitOn = headersToSplitOn.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getKey().length()))
                .collect(Collectors.toList());
        this.returnEachLine = returnEachLine;
        this.stripHeaders = stripHeaders;
        this.parentChildModel = parentChildModel;
    }

    /**
     * 重写apply方法以支持元数据的传递
     */
    @Override
    public List<Document> apply(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            List<DocumentWithMetadata> segments = splitWithMetadata(doc.getText(), doc.getMetadata());
            for (DocumentWithMetadata segment : segments) {
                result.add(new Document(segment.getContent(), segment.getMetadata()));
            }
        }
        return result;
    }

    /**
     * 简化版分割方法，不保留元数据
     *
     * @param text 待分割的文本
     * @return 分割后的文本片段列表
     */
    @Override
    protected List<String> splitText(String text) {
        return splitWithMetadata(text, new HashMap<>()).stream()
                .map(DocumentWithMetadata::getContent)
                .collect(Collectors.toList());
    }

    /**
     * 核心分割逻辑，保留元数据
     *
     * @param text 待分割的文本
     * @param baseMetadata 基础元数据，会被传递到每个分段中
     * @return 带有元数据的文档片段列表
     */
    private List<DocumentWithMetadata> splitWithMetadata(String text, Map<String, Object> baseMetadata) {
        List<String> lines = Arrays.asList(text.split("\n"));
        List<Line> linesWithMetadata = new ArrayList<>();
        List<String> currentContent = new ArrayList<>();
        Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
        List<Header> headerStack = new ArrayList<>();
        Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);

        boolean inCodeBlock = false;
        String openingFence = "";

        for (String line : lines) {
            String strippedLine = line.trim();

            // 处理代码块标记，代码块内的内容不作为标题处理
            if (!inCodeBlock) {
                if (strippedLine.startsWith("```")) {
                    inCodeBlock = !inCodeBlock;
                    openingFence = "```";
                } else if (strippedLine.startsWith("~~~")) {
                    inCodeBlock = !inCodeBlock;
                    openingFence = "~~~";
                }
            } else {
                if (strippedLine.startsWith(openingFence)) {
                    inCodeBlock = false;
                    openingFence = "";
                }
            }

            // 代码块内的内容直接添加，不做标题检测
            if (inCodeBlock) {
                currentContent.add(strippedLine);
                continue;
            }

            // 检测并处理标题行
            interrupted:
            {
                for (Map.Entry<String, String> header : headersToSplitOn) {
                    String sep = header.getKey();
                    String name = header.getValue();

                    if (strippedLine.startsWith(sep)) {
                        if (name != null) {
                            int currentHeaderLevel = (int) sep.chars().filter(ch -> ch == '#').count();

                            while (!headerStack.isEmpty() && headerStack.get(headerStack.size() - 1).getLevel() >= currentHeaderLevel) {
                                Header poppedHeader = headerStack.remove(headerStack.size() - 1);
                                initialMetadata.remove(poppedHeader.getName());
                            }

                            Header headerType = new Header(currentHeaderLevel, name, strippedLine.substring(sep.length()).trim());
                            headerStack.add(headerType);
                            initialMetadata.put(name, headerType.getData());
                            initialMetadata.put("headerLevel", currentHeaderLevel);
                            String currentChunkId = UUID.randomUUID().toString();
                            initialMetadata.put("chunkId", currentChunkId);
                        }

                        if (!currentContent.isEmpty()) {
                            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                            currentContent.clear();
                        }

                        if (!stripHeaders) {
                            currentContent.add(strippedLine);
                        }

                        break interrupted;
                    }
                }

                // 处理非标题行
                if (!strippedLine.isEmpty()) {
                    currentContent.add(strippedLine);
                } else if (!currentContent.isEmpty()) {
                    linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                    currentContent.clear();
                }
            }

            currentMetadata = new HashMap<>(initialMetadata);
        }

        if (!currentContent.isEmpty()) {
            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
        }

        List<DocumentWithMetadata> segments;
        if (!returnEachLine) {
            segments = aggregateLinesToChunks(linesWithMetadata);
        } else {
            segments = linesWithMetadata.stream()
                    .map(line -> new DocumentWithMetadata(line.getContent(), line.getMetadata()))
                    .collect(Collectors.toList());
        }

        return segments;
    }

    /**
     * 判断两个元数据是否属于同一标题层级上下文
     * 只比较headersToSplitOn中定义的标题key和headerLevel，忽略chunkId等字段
     */
    private boolean hasSameHeadingContext(Map<String, Object> m1, Map<String, Object> m2) {
        for (Map.Entry<String, String> header : headersToSplitOn) {
            String metadataKey = header.getValue();
            if (!Objects.equals(m1.get(metadataKey), m2.get(metadataKey))) {
                return false;
            }
        }
        return Objects.equals(m1.get("headerLevel"), m2.get("headerLevel"));
    }

    /**
     * 判断一个分块是否仅包含标题行（无正文内容）
     * 用于决定是否将该标题与后续内容合并
     */
    private boolean isHeadingOnlyChunk(Line chunk) {
        String content = chunk.getContent();
        return content.lines().allMatch(line -> line.trim().startsWith("#") || line.trim().isEmpty());
    }

    /**
     * 聚合行为分块
     * 将具有相同标题层级上下文的行合并为一个分块，并处理父子关系
     *
     * @param lines 待聚合的行列表
     * @return 聚合后的文档片段列表
     */
    private List<DocumentWithMetadata> aggregateLinesToChunks(List<Line> lines) {
        List<Line> aggregatedChunks = new ArrayList<>();
        for (Line line : lines) {
            Line last = aggregatedChunks.isEmpty() ? null : aggregatedChunks.get(aggregatedChunks.size() - 1);

            if (last == null) {
                aggregatedChunks.add(line);
                continue;
            }

            boolean sameContext = hasSameHeadingContext(last.getMetadata(), line.getMetadata());

            if (sameContext) {
                // 相同标题上下文，合并
                last.setContent(last.getContent() + "  \n" + line.getContent());
            } else if (!stripHeaders && isHeadingOnlyChunk(last)) {
                // 上一个分块仅含标题行（无正文），将其与后续内容合并
                last.setContent(last.getContent() + "  \n" + line.getContent());
            } else {
                aggregatedChunks.add(line);
            }
        }

        // 处理父子分段关系（双向）
        if (parentChildModel) {
            buildParentChildRelations(aggregatedChunks);
        }

        return aggregatedChunks.stream()
                .map(chunk -> new DocumentWithMetadata(chunk.getContent(), chunk.getMetadata()))
                .collect(Collectors.toList());
    }

    /**
     * 建立双向父子关系
     * 子chunk通过parentChunkId指向父chunk
     * 父chunk通过childChunkIds持有所有直接子chunk的ID列表
     */
    private void buildParentChildRelations(List<Line> chunks) {
        try {
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> currentMetaData = chunks.get(i).getMetadata();
                Integer headerLevel = (Integer) currentMetaData.get("headerLevel");

                if (headerLevel == null || headerLevel == 1) {
                    continue;
                }

                if (headerLevel > 1) {
                    for (int j = i - 1; j >= 0; j--) {
                        Map<String, Object> parentMetaData = chunks.get(j).getMetadata();
                        Integer parentHeaderLevel = (Integer) parentMetaData.get("headerLevel");
                        if (parentHeaderLevel != null && parentHeaderLevel < headerLevel) {
                            String parentChunkId = (String) parentMetaData.get("chunkId");
                            String childChunkId = (String) currentMetaData.get("chunkId");

                            // 子 → 父
                            currentMetaData.put("parentChunkId", parentChunkId);

                            // 父 → 子（双向）
                            @SuppressWarnings("unchecked")
                            List<String> childChunkIds = (List<String>) parentMetaData.computeIfAbsent(
                                    "childChunkIds", k -> new ArrayList<>());
                            childChunkIds.add(childChunkId);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("父子模式转换失败: {}", e.getMessage());
        }
    }

    /**
     * 内部类：表示带有元数据的文本行
     */
    public static class Line {
        private String content;
        private Map<String, Object> metadata;

        public Line(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = metadata;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }

    /**
     * 内部类：表示Markdown标题
     */
    public static class Header {
        private int level;
        private String name;
        private String data;

        public Header(int level, String name, String data) {
            this.level = level;
            this.name = name;
            this.data = data;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }

    /**
     * 内部类：携带元数据的文档片段
     */
    private static class DocumentWithMetadata {
        private final String content;
        private final Map<String, Object> metadata;

        public DocumentWithMetadata(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = new HashMap<>(metadata);
        }

        public String getContent() {
            return content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }
}
