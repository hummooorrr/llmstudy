package cn.wzw.llm.study.llmstudy.util;

import cn.wzw.llm.study.llmstudy.config.RetrievalProperties;
import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import cn.wzw.llm.study.llmstudy.rerank.RerankProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pro-Rag 检索结果重排序工具：提供 RRF 融合、文件级聚合和 Rerank 精排三种策略。
 * Rerank 精排通过 {@link RerankProvider} 接口调用，支持灵活切换供应商。
 */
@Slf4j
@Component
public class ProRagRerankUtil {

    @Value("${pro-rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Autowired
    private RetrievalProperties retrievalProperties;

    @Autowired(required = false)
    private RerankProvider rerankProvider;

    /**
     * 融合后每个候选的完整信息：用于让上游同时拿到"LLM 可见顺序"和"docId + metadata"。
     * 保证 references 角标与 prompt 中材料顺序 1:1 对应。
     */
    public record FusedChunk(
            String docId,
            String text,
            Map<String, Object> metadata,
            double score
    ) {}

    /**
     * 混合检索 + 融合：先 RRF 粗排，再 Rerank 精排
     */
    public List<String> hybridFusion(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs,
                                     String query, int topK) {
        return hybridFusionDetailed(vectorDocs, keywordDocs, query, topK).stream()
                .map(FusedChunk::text)
                .collect(Collectors.toList());
    }

    /**
     * 同 hybridFusion，但返回带 docId/metadata/score 的完整结构，保留融合顺序。
     * 上游据此一次性构造 prompt contents + references，避免两者顺序/数量错位。
     */
    public List<FusedChunk> hybridFusionDetailed(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs,
                                                 String query, int topK) {
        // Step 1: RRF 粗排，候选池要比 topK 大，给 Rerank 留充足的重排空间
        int candidatePoolSize = Math.max(topK,
                retrievalProperties.getRerank().getCandidatePoolSize());
        List<FusedChunk> rrfCandidates = rrfFusionDetailed(vectorDocs, keywordDocs, candidatePoolSize);

        // Step 2: Rerank 精排（如果启用且有 provider），从候选池中挑出 topK
        if (rerankEnabled && rerankProvider != null) {
            try {
                List<FusedChunk> result = rerankProvider.rerank(rrfCandidates, query, topK);
                if (!result.isEmpty()) {
                    return result;
                }
                log.warn("Rerank 返回空结果，回退到 RRF 融合");
            } catch (Exception e) {
                log.warn("Rerank 调用失败，回退到 RRF 融合: {}", e.getMessage());
            }
        }
        // 没启用 Rerank 或 Rerank 失败，截断到 topK 返回
        return rrfCandidates.size() > topK ? rrfCandidates.subList(0, topK) : rrfCandidates;
    }

    /**
     * RRF 算法融合向量检索和关键词检索结果
     */
    public List<String> rrfFusion(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs, int topK) {
        return rrfFusionDetailed(vectorDocs, keywordDocs, topK).stream()
                .map(FusedChunk::text)
                .collect(Collectors.toList());
    }

    /**
     * RRF 融合的 detailed 版本：返回排序后的 FusedChunk 列表。
     */
    public List<FusedChunk> rrfFusionDetailed(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs, int topK) {
        final int K = retrievalProperties.getRerank().getRrfK();
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, String> idToContent = new LinkedHashMap<>();
        Map<String, Map<String, Object>> idToMeta = new LinkedHashMap<>();

        for (int i = 0; i < vectorDocs.size(); i++) {
            Document doc = vectorDocs.get(i);
            String docId = doc.getId();
            int rank = i + 1;
            double score = 1.0 / (K + rank);
            rrfScores.merge(docId, score, Double::sum);
            idToContent.putIfAbsent(docId, doc.getText());
            idToMeta.putIfAbsent(docId, doc.getMetadata());
        }

        for (int i = 0; i < keywordDocs.size(); i++) {
            EsDocumentChunk doc = keywordDocs.get(i);
            String docId = doc.getId();
            int rank = i + 1;
            double score = 1.0 / (K + rank);
            rrfScores.merge(docId, score, Double::sum);
            idToContent.putIfAbsent(docId, doc.getContent());
            idToMeta.putIfAbsent(docId, doc.getMetadata());
        }

        List<String> sortedDocIds = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(topK)
                .toList();

        List<FusedChunk> result = new ArrayList<>(sortedDocIds.size());
        for (String docId : sortedDocIds) {
            String text = idToContent.get(docId);
            if (text == null) {
                continue;
            }
            result.add(new FusedChunk(docId, text, idToMeta.get(docId), rrfScores.getOrDefault(docId, 0.0)));
        }

        String scoresLog = result.stream()
                .map(c -> String.format("docId=%s, rrf=%.4f", c.docId(), c.score()))
                .collect(Collectors.joining("; "));
        log.info("RRF融合后top{}结果：{}", topK, scoresLog);

        return result;
    }

    /**
     * RRF融合后按filePath聚合，返回文件级别的相关度排序
     */
    public List<Map<String, Object>> rrfFusionGroupByFilePath(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs) {
        final int K = retrievalProperties.getRerank().getRrfK();
        Map<String, Double> filePathScores = new LinkedHashMap<>();
        Map<String, String> filePathToFilename = new LinkedHashMap<>();
        Map<String, Integer> filePathChunkCount = new LinkedHashMap<>();

        for (int i = 0; i < vectorDocs.size(); i++) {
            Document doc = vectorDocs.get(i);
            int rank = i + 1;
            double score = 1.0 / (K + rank);
            String filePath = doc.getMetadata().getOrDefault("filePath", "unknown").toString();
            String filename = doc.getMetadata().getOrDefault("filename", "unknown").toString();
            filePathScores.merge(filePath, score, Double::sum);
            filePathToFilename.putIfAbsent(filePath, filename);
            filePathChunkCount.merge(filePath, 1, Integer::sum);
        }

        for (int i = 0; i < keywordDocs.size(); i++) {
            EsDocumentChunk doc = keywordDocs.get(i);
            int rank = i + 1;
            double score = 1.0 / (K + rank);
            String filePath = doc.getMetadata() != null
                    ? doc.getMetadata().getOrDefault("filePath", "unknown").toString()
                    : "unknown";
            String filename = doc.getMetadata() != null
                    ? doc.getMetadata().getOrDefault("filename", "unknown").toString()
                    : "unknown";
            filePathScores.merge(filePath, score, Double::sum);
            filePathToFilename.putIfAbsent(filePath, filename);
            filePathChunkCount.merge(filePath, 1, Integer::sum);
        }

        List<Map<String, Object>> result = filePathScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("filePath", entry.getKey());
                    item.put("filename", filePathToFilename.getOrDefault(entry.getKey(), "unknown"));
                    item.put("score", Math.round(entry.getValue() * 10000.0) / 10000.0);
                    item.put("matchedChunks", filePathChunkCount.getOrDefault(entry.getKey(), 0));
                    return item;
                })
                .collect(Collectors.toList());

        log.info("文件定位结果：共{}个文件命中，按得分排序：{}",
                result.size(),
                result.stream().map(m -> m.get("filename") + "(" + m.get("score") + ")").collect(Collectors.joining(", "))
        );

        return result;
    }

    /**
     * 使用 RerankProvider 重排序（如果已注入）。
     *
     * @deprecated 请使用 {@link #hybridFusionDetailed}，该方法内部自动调用 RerankProvider
     */
    @Deprecated
    public List<String> rerankFusion(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs,
                                     String query, int topK) throws Exception {
        return hybridFusionDetailed(vectorDocs, keywordDocs, query, topK).stream()
                .map(FusedChunk::text)
                .collect(Collectors.toList());
    }

    /**
     * @deprecated 请使用 {@link #hybridFusionDetailed}，该方法内部自动调用 RerankProvider
     */
    @Deprecated
    public List<FusedChunk> rerankFusionDetailed(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs,
                                                 String query, int topK) throws Exception {
        return hybridFusionDetailed(vectorDocs, keywordDocs, query, topK);
    }
}
