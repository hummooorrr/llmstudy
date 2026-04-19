package cn.wzw.llm.study.llmstudy;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 滑动窗口句子分片器
 * 将文档按句子切分，每个句子作为独立的检索单元，
 * 同时在元数据中保存其前后窗口句子，检索命中后可将窗口内容送给LLM以提供更完整的上下文
 *
 * @author Hollis
 */
public class SentenceWindowSplitter extends TextSplitter {

    /** 窗口大小：每个句子前后各保留多少个句子作为上下文 */
    private final int windowSize;

    /** 句子分隔正则：匹配中英文句末标点 */
    private static final Pattern SENTENCE_PATTERN = Pattern.compile(
            "[^。！？.!?\n]+[。！？.!?\n]?"
    );

    /**
     * 构造函数
     *
     * @param windowSize 窗口大小，每个句子前后各保留多少个句子（例如 windowSize=2，则窗口共 5 句）
     */
    public SentenceWindowSplitter(int windowSize) {
        if (windowSize < 0) {
            throw new IllegalArgumentException("windowSize 不能为负数");
        }
        this.windowSize = windowSize;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return Collections.emptyList();
        }

        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            result.addAll(splitWithWindow(doc.getText(), doc.getMetadata()));
        }
        return result;
    }

    @Override
    protected List<String> splitText(String text) {
        return splitIntoSentences(text);
    }

    /**
     * 核心方法：按句子切分并为每个句子附加窗口上下文
     *
     * @param text          原始文本
     * @param baseMetadata  基础元数据
     * @return 带窗口元数据的句子Document列表
     */
    private List<Document> splitWithWindow(String text, Map<String, Object> baseMetadata) {
        List<String> sentences = splitIntoSentences(text);
        if (sentences.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> result = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);

            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put("sentenceIndex", i);
            metadata.put("totalSentences", sentences.size());

            // 构建窗口上下文
            String windowContext = buildWindowContext(sentences, i);
            metadata.put("windowContext", windowContext);

            result.add(new Document(sentence, metadata));
        }
        return result;
    }

    /**
     * 构建指定句子的窗口上下文（前 windowSize 句 + 当前句 + 后 windowSize 句）
     */
    private String buildWindowContext(List<String> sentences, int currentIndex) {
        int start = Math.max(0, currentIndex - windowSize);
        int end = Math.min(sentences.size() - 1, currentIndex + windowSize);

        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) {
                sb.append(" ");
            }
            sb.append(sentences.get(i).trim());
        }
        return sb.toString();
    }

    /**
     * 将文本拆分为句子列表
     */
    private List<String> splitIntoSentences(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            String sentence = matcher.group().trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }
}
