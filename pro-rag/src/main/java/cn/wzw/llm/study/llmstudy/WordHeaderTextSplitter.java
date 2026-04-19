package cn.wzw.llm.study.llmstudy;


import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.ai.document.Document;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Word文档分割器，基于标题样式层级进行文档分段
 * 支持保留元数据、父子分段关系等高级特性
 *
 * @author Hollis
 */
public class WordHeaderTextSplitter extends OverlapParagraphTextSplitter {

    /**
     * 需要分割的标题级别列表（1-9），对应Word的标题样式
     */
    private List<Integer> headingLevelsToSplitOn;

    /**
     * 是否按段落返回结果
     */
    private boolean returnEachParagraph;

    /**
     * 是否剥离标题段落本身
     */
    private boolean stripHeadings;

    /**
     * 是否启用父子分段模式
     */
    private boolean parentChildModel;

    /**
     * 用于聚合比较的标题相关元数据key前缀
     */
    private static final String HEADING_KEY_PREFIX = "heading";

    /**
     * 构造函数（支持chunkSize和overlap）
     *
     * @param headingLevelsToSplitOn 标题级别列表，如Arrays.asList(1, 2, 3)表示分割标题1、2、3
     * @param returnEachParagraph    是否按段落返回结果，false时会聚合相同元数据的段落
     * @param stripHeadings          是否在结果中移除标题段落
     * @param parentChildModel       是否启用父子分段模式，启用后会在元数据中添加parentChunkId和childChunkIds
     * @param chunkSize              每块最大字符数，0表示不限制
     * @param overlap                相邻块之间重叠字符数，0表示不重叠
     */
    public WordHeaderTextSplitter(List<Integer> headingLevelsToSplitOn, boolean returnEachParagraph,
                                  boolean stripHeadings, boolean parentChildModel,
                                  int chunkSize, int overlap) {
        super(chunkSize, overlap);
        this.headingLevelsToSplitOn = headingLevelsToSplitOn != null ?
                new ArrayList<>(headingLevelsToSplitOn) : Arrays.asList(1, 2, 3, 4, 5, 6);
        // 按级别排序，确保从小到大
        Collections.sort(this.headingLevelsToSplitOn);
        this.returnEachParagraph = returnEachParagraph;
        this.stripHeadings = stripHeadings;
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
            try {
                Object wordInputStream = doc.getMetadata().get("wordInputStream");
                if (wordInputStream instanceof InputStream) {
                    try {
                        List<DocumentWithMetadata> segments = splitWordDocument((InputStream) wordInputStream, doc.getMetadata());
                        for (DocumentWithMetadata segment : segments) {
                            result.add(new Document(segment.getContent(), segment.getMetadata()));
                        }
                    } finally {
                        ((InputStream) wordInputStream).close();
                    }
                } else if (wordInputStream instanceof byte[]) {
                    try (InputStream is = new ByteArrayInputStream((byte[]) wordInputStream)) {
                        List<DocumentWithMetadata> segments = splitWordDocument(is, doc.getMetadata());
                        for (DocumentWithMetadata segment : segments) {
                            result.add(new Document(segment.getContent(), segment.getMetadata()));
                        }
                    }
                } else {
                    // 如果没有提供输入流，尝试从文本内容解析（兜底方案）
                    List<DocumentWithMetadata> segments = splitPlainText(doc.getText(), doc.getMetadata());
                    for (DocumentWithMetadata segment : segments) {
                        result.add(new Document(segment.getContent(), segment.getMetadata()));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Word文档分割失败: " + e.getMessage(), e);
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
    public List<String> splitText(String text) {
        return splitPlainText(text, new HashMap<>()).stream()
                .map(DocumentWithMetadata::getContent)
                .collect(Collectors.toList());
    }

    /**
     * 核心分割逻辑 - 处理Word文档
     *
     * @param inputStream  Word文档输入流
     * @param baseMetadata 基础元数据，会被传递到每个分段中
     * @return 带有元数据的文档片段列表
     */
    private List<DocumentWithMetadata> splitWordDocument(InputStream inputStream, Map<String, Object> baseMetadata) throws Exception {
        // 检测文件格式（.doc 或 .docx）
        baseMetadata.remove("wordInputStream");
        BufferedInputStream bis = new BufferedInputStream(inputStream);
        bis.mark(8192); // 标记位置，以便重新读取

        FileMagic fileMagic = FileMagic.valueOf(bis);
        bis.reset(); // 重置流到标记位置

        // 根据文件格式选择不同的处理方法
        if (fileMagic == FileMagic.OLE2) {
            // 旧格式 .doc 文件
            return splitDocDocument(bis, baseMetadata);
        } else if (fileMagic == FileMagic.OOXML) {
            // 新格式 .docx 文件
            return splitDocxDocument(bis, baseMetadata);
        } else {
            throw new IllegalArgumentException("不支持的文件格式，仅支持 .doc 和 .docx 文件");
        }
    }

    /**
     * 处理新格式Word文档 (.docx)
     * 使用try-with-resources确保XWPFDocument正确关闭
     */
    private List<DocumentWithMetadata> splitDocxDocument(InputStream inputStream, Map<String, Object> baseMetadata) throws Exception {
        List<ParagraphWithMetadata> paragraphsWithMetadata = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            List<String> currentContent = new ArrayList<>();
            Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
            List<HeadingInfo> headingStack = new ArrayList<>();
            Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);

            // 遍历所有段落
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText().trim();
                if (text.isEmpty()) {
                    continue;
                }

                // 获取段落样式
                String style = paragraph.getStyle();
                Integer headingLevel = extractHeadingLevelFromDocx(style, paragraph);

                // 检测并处理标题段落
                if (headingLevel != null && headingLevelsToSplitOn.contains(headingLevel)) {
                    // 维护标题栈：移除所有级别大于等于当前级别的标题
                    while (!headingStack.isEmpty() && headingStack.get(headingStack.size() - 1).getLevel() >= headingLevel) {
                        HeadingInfo poppedHeading = headingStack.remove(headingStack.size() - 1);
                        initialMetadata.remove(poppedHeading.getMetadataKey());
                    }

                    // 将当前标题加入栈，并更新元数据
                    String metadataKey = "heading" + headingLevel;
                    HeadingInfo headingInfo = new HeadingInfo(headingLevel, metadataKey, text);
                    headingStack.add(headingInfo);
                    initialMetadata.put(metadataKey, text);
                    initialMetadata.put("headingLevel", headingLevel);
                    // 为每个分段生成唯一ID，用于后续建立父子关系
                    String currentChunkId = UUID.randomUUID().toString();
                    initialMetadata.put("chunkId", currentChunkId);

                    // 遇到新标题时，保存之前累积的内容
                    if (!currentContent.isEmpty()) {
                        paragraphsWithMetadata.add(new ParagraphWithMetadata(String.join("\n", currentContent), currentMetadata));
                        currentContent.clear();
                    }

                    // 根据stripHeadings配置决定是否保留标题段落
                    if (!stripHeadings) {
                        currentContent.add(text);
                    }
                } else {
                    // 处理非标题段落
                    currentContent.add(text);
                }

                // 更新当前元数据为最新的标题信息
                currentMetadata = new HashMap<>(initialMetadata);
            }

            // 处理最后累积的内容
            if (!currentContent.isEmpty()) {
                paragraphsWithMetadata.add(new ParagraphWithMetadata(String.join("\n", currentContent), currentMetadata));
            }

            // 提取表格中的文本内容
            for (var table : document.getTables()) {
                for (var row : table.getRows()) {
                    StringBuilder rowText = new StringBuilder();
                    for (var cell : row.getTableCells()) {
                        String cellText = cell.getText().trim();
                        if (!cellText.isEmpty()) {
                            if (rowText.length() > 0) {
                                rowText.append(" ");
                            }
                            rowText.append(cellText);
                        }
                    }
                    if (rowText.length() > 0) {
                        Map<String, Object> tableMetadata = new HashMap<>(baseMetadata);
                        tableMetadata.put("source", "table");
                        paragraphsWithMetadata.add(new ParagraphWithMetadata(rowText.toString(), tableMetadata));
                    }
                }
            }
        }
        // XWPFDocument已自动关闭
        return processSegments(paragraphsWithMetadata);
    }

    /**
     * 处理旧格式Word文档 (.doc)
     * 使用try-with-resources确保HWPFDocument正确关闭
     */
    private List<DocumentWithMetadata> splitDocDocument(InputStream inputStream, Map<String, Object> baseMetadata) throws Exception {
        List<ParagraphWithMetadata> paragraphsWithMetadata = new ArrayList<>();
        try (HWPFDocument document = new HWPFDocument(inputStream)) {
            Range range = document.getRange();
            List<String> currentContent = new ArrayList<>();
            Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
            List<HeadingInfo> headingStack = new ArrayList<>();
            Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);

            // 遍历所有段落
            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph paragraph = range.getParagraph(i);
                String text = paragraph.text().trim();

                if (text.isEmpty()) {
                    continue;
                }

                // 优先通过样式检测标题，再通过文本模式兜底
                Integer headingLevel = extractHeadingLevelFromDoc(document, paragraph);
                if (headingLevel == null) {
                    headingLevel = detectHeadingByTextPattern(text);
                }

                // 检测并处理标题段落
                if (headingLevel != null && headingLevelsToSplitOn.contains(headingLevel)) {
                    // 维护标题栈
                    while (!headingStack.isEmpty() && headingStack.get(headingStack.size() - 1).getLevel() >= headingLevel) {
                        HeadingInfo poppedHeading = headingStack.remove(headingStack.size() - 1);
                        initialMetadata.remove(poppedHeading.getMetadataKey());
                    }

                    // 将当前标题加入栈，并更新元数据
                    String metadataKey = "heading" + headingLevel;
                    HeadingInfo headingInfo = new HeadingInfo(headingLevel, metadataKey, text);
                    headingStack.add(headingInfo);
                    initialMetadata.put(metadataKey, text);
                    initialMetadata.put("headingLevel", headingLevel);
                    String currentChunkId = UUID.randomUUID().toString();
                    initialMetadata.put("chunkId", currentChunkId);

                    // 遇到新标题时，保存之前累积的内容
                    if (!currentContent.isEmpty()) {
                        paragraphsWithMetadata.add(new ParagraphWithMetadata(String.join("\n", currentContent), currentMetadata));
                        currentContent.clear();
                    }

                    // 根据stripHeadings配置决定是否保留标题段落
                    if (!stripHeadings) {
                        currentContent.add(text);
                    }
                } else {
                    // 处理非标题段落
                    currentContent.add(text);
                }

                // 更新当前元数据
                currentMetadata = new HashMap<>(initialMetadata);
            }

            // 处理最后累积的内容
            if (!currentContent.isEmpty()) {
                paragraphsWithMetadata.add(new ParagraphWithMetadata(String.join("\n", currentContent), currentMetadata));
            }
        }
        // HWPFDocument已自动关闭
        return processSegments(paragraphsWithMetadata);
    }

    /**
     * 处理段落列表，返回最终的文档片段
     */
    private List<DocumentWithMetadata> processSegments(List<ParagraphWithMetadata> paragraphsWithMetadata) {
        List<DocumentWithMetadata> segments;
        if (!returnEachParagraph) {
            // 聚合模式：将相同元数据的段落合并
            segments = aggregateParagraphsToChunks(paragraphsWithMetadata);
        } else {
            // 逐段模式：保持每段独立
            segments = paragraphsWithMetadata.stream()
                    .map(para -> new DocumentWithMetadata(para.getContent(), para.getMetadata()))
                    .collect(Collectors.toList());
        }
        return segments;
    }

    /**
     * 从.doc段落中提取标题级别（通过样式表）
     * 如果样式表中没有标题样式信息，返回null由文本模式兜底
     */
    private Integer extractHeadingLevelFromDoc(HWPFDocument document, Paragraph paragraph) {
        try {
            String styleName = document.getStyleSheet().getStyleDescription(paragraph.getStyleIndex()).getName();
            if (styleName != null && (styleName.matches("(?i)heading\\s*\\d") || styleName.matches("标题\\s*\\d"))) {
                String levelStr = styleName.replaceAll("(?i)heading|标题|\\s", "");
                return Integer.parseInt(levelStr);
            }
        } catch (Exception e) {
            // 样式表解析失败，交给文本模式兜底
        }
        return null;
    }

    /**
     * 从段落中提取标题级别 (.docx格式)
     *
     * @param style     段落样式名称
     * @param paragraph 段落对象
     * @return 标题级别（1-9），如果不是标题则返回null
     */
    private Integer extractHeadingLevelFromDocx(String style, XWPFParagraph paragraph) {
        // 方法1: 标准Word标题样式：Heading1, Heading2, ..., Heading9
        // 或中文版：标题 1, 标题 2, ...
        Integer headingLevel = null;
        if (style != null && (style.matches("(?i)heading\\s*\\d") || style.matches("标题\\s*\\d"))) {
            try {
                String levelStr = style.replaceAll("(?i)heading|标题|\\s", "");
                headingLevel = Integer.parseInt(levelStr);
            } catch (NumberFormatException e) {
                // 继续尝试其他方法
            }
        }

        // 方法2: 基于文本内容的模式匹配（用于没有应用标准样式的文档）
        if (headingLevel == null) {
            String text = paragraph.getText();
            if (text != null && !text.isEmpty()) {
                Integer level = detectHeadingByTextPattern(text.trim());
                if (level != null && level > 0) {
                    headingLevel = level;
                }
            }
        }

        return headingLevel;
    }

    /**
     * 通过文本模式检测标题级别
     * 适用于没有应用Word标准样式的文档
     * <p>
     * 注意：此方法采用保守策略，只匹配结构性标题模式（第X章、一、等），
     * 避免基于关键词的宽泛匹配导致正文段落被误判为标题
     *
     * @param text 段落文本内容
     * @return 标题级别，如果不是标题则返回null
     */
    private Integer detectHeadingByTextPattern(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // 匹配 "第X章" 或 "第X部分"
        if (text.matches("^第[一二三四五六七八九十百]+章.*")) {
            return 1;
        }
        if (text.matches("^第[一二三四五六七八九十百]+部分.*")) {
            return 1;
        }

        if (text.matches("^第[一二三四五六七八九十百]+条.*")) {
            return 2;
        }

        // 匹配 "（一）" "（二）" 等
        if (text.matches("^[（(][一二三四五六七八九十百]+[)）].*")) {
            return 3;
        }

        // 匹配 "一、" "二、" 等
        if (text.matches("^[一二三四五六七八九十百]+、.*")) {
            return 2;
        }

        // 匹配 "1." "2." 等数字标题（后面不能紧跟数字，避免误判如 "1.1"）
        if (text.matches("^\\d+\\.\\s*[^0-9].*")) {
            return 3;
        }

        // 匹配 "(1)" "(2)" 等
        if (text.matches("^[（(]\\d+[)）].*")) {
            return 3;
        }

        // 匹配 "1.1" "1.2" 等多级编号
        if (text.matches("^\\d+\\.\\d+.*")) {
            return 4;
        }

        // 纯中文极短文本精确匹配法规章节关键词（要求完全等于关键词，避免包含匹配误判）
        if (text.length() <= 10 && text.matches("^[一-龥]+$")) {
            if (text.equals("总则") || text.equals("附则") || text.equals("说明") ||
                    text.equals("须知") || text.equals("规定") || text.equals("制度") ||
                    text.equals("办法") || text.equals("条例")) {
                return 1;
            }
        }

        // 短文本以特定复合词结尾，且不含句内标点（确保是独立标题行而非正文片段）
        if (text.length() <= 15 && !text.matches(".*[，。；：、,;].*")) {
            if (text.endsWith("管理制度") || text.endsWith("管理办法") ||
                    text.endsWith("管理规定") || text.endsWith("管理规范") ||
                    text.endsWith("暂行规定") || text.endsWith("实施细则")) {
                return 2;
            }
        }

        return null;
    }

    /**
     * 兜底方案：处理纯文本（当无法解析Word文档时）
     */
    private List<DocumentWithMetadata> splitPlainText(String text, Map<String, Object> baseMetadata) {
        baseMetadata.remove("wordInputStream");
        List<DocumentWithMetadata> result = new ArrayList<>();
        Map<String, Object> metadata = new HashMap<>(baseMetadata);
        metadata.put("chunkId", UUID.randomUUID().toString());
        result.add(new DocumentWithMetadata(text, metadata));
        return result;
    }

    /**
     * 判断两个元数据是否属于同一标题层级上下文
     * 只比较heading相关的key，忽略chunkId等会随段落变化的字段
     */
    private boolean hasSameHeadingContext(Map<String, Object> m1, Map<String, Object> m2) {
        for (int level = 1; level <= 6; level++) {
            String key = HEADING_KEY_PREFIX + level;
            if (!Objects.equals(m1.get(key), m2.get(key))) {
                return false;
            }
        }
        return Objects.equals(m1.get("headingLevel"), m2.get("headingLevel"));
    }

    /**
     * 聚合段落为分块
     * 将具有相同标题层级上下文的段落合并为一个分块，并处理父子关系
     *
     * @param paragraphs 待聚合的段落列表
     * @return 聚合后的文档片段列表
     */
    private List<DocumentWithMetadata> aggregateParagraphsToChunks(List<ParagraphWithMetadata> paragraphs) {
        List<ParagraphWithMetadata> aggregatedChunks = new ArrayList<>();

        for (ParagraphWithMetadata paragraph : paragraphs) {
            // 标题层级上下文相同，合并到上一个分块
            if (!aggregatedChunks.isEmpty() &&
                    hasSameHeadingContext(aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata(), paragraph.getMetadata())) {
                ParagraphWithMetadata last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "\n" + paragraph.getContent());
            } else {
                // 创建新分块
                aggregatedChunks.add(paragraph);
            }
        }

        // 如果启用了chunkSize限制，需要对聚合后的分块再次按大小分割
        if (chunkSize > 0) {
            aggregatedChunks = applySizeBasedSplitting(aggregatedChunks);
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
    private void buildParentChildRelations(List<ParagraphWithMetadata> chunks) {
        try {
            for (int i = 0; i < chunks.size(); i++) {
                Map<String, Object> currentMetaData = chunks.get(i).getMetadata();
                Integer headingLevel = (Integer) currentMetaData.get("headingLevel");

                // 顶级标题（level=1）或无标题的分块跳过
                if (headingLevel == null || headingLevel == 1) {
                    continue;
                }

                // 向前查找第一个级别更低的标题作为父节点
                if (headingLevel > 1) {
                    for (int j = i - 1; j >= 0; j--) {
                        Map<String, Object> parentMetaData = chunks.get(j).getMetadata();
                        Integer parentHeadingLevel = (Integer) parentMetaData.get("headingLevel");
                        if (parentHeadingLevel != null && parentHeadingLevel < headingLevel) {
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
            System.out.println("父子模式转换失败，" + e.getMessage());
        }
    }

    /**
     * 根据chunkSize对已聚合的分块进行二次分割
     *
     * @param chunks 已聚合的分块列表
     * @return 按大小分割后的分块列表
     */
    private List<ParagraphWithMetadata> applySizeBasedSplitting(List<ParagraphWithMetadata> chunks) {
        List<ParagraphWithMetadata> result = new ArrayList<>();

        for (ParagraphWithMetadata chunk : chunks) {
            String content = chunk.getContent();
            Map<String, Object> metadata = chunk.getMetadata();

            // 如果内容长度小于等于chunkSize，直接保留，不添加overlap
            if (content.length() <= chunkSize) {
                result.add(chunk);
                continue;
            }

            // 内容超过chunkSize，需要分段并添加overlap
            List<String> subContents = super.splitText(content);
            for (int i = 0; i < subContents.size(); i++) {
                // 为每个子分段创建新的元数据，保留原有信息并添加分段索引
                Map<String, Object> segmentMetadata = new HashMap<>(metadata);
                // 为子分段生成新的chunkId，但保留原始的headingLevel等信息
                String originalChunkId = (String) metadata.get("chunkId");
                segmentMetadata.put("chunkId", originalChunkId + "_" + i);
                segmentMetadata.put("segmentIndex", i);
                segmentMetadata.put("isSplit", true);

                result.add(new ParagraphWithMetadata(subContents.get(i), segmentMetadata));
            }
        }

        return result;
    }

    /**
     * 内部类：表示带有元数据的段落
     */
    private static class ParagraphWithMetadata {
        private String content;
        private Map<String, Object> metadata;

        public ParagraphWithMetadata(String content, Map<String, Object> metadata) {
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
     * 内部类：表示Word标题信息
     */
    private static class HeadingInfo {
        /**
         * 标题级别（1-9）
         */
        private int level;
        /**
         * 元数据中的键名
         */
        private String metadataKey;
        /**
         * 标题文本内容
         */
        private String text;

        public HeadingInfo(int level, String metadataKey, String text) {
            this.level = level;
            this.metadataKey = metadataKey;
            this.text = text;
        }

        public int getLevel() {
            return level;
        }

        public String getMetadataKey() {
            return metadataKey;
        }

        public String getText() {
            return text;
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
