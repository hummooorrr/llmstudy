package cn.wzw.llm.study.llmstudy.util;

import cn.wzw.llm.study.llmstudy.config.RetrievalProperties;
import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/** Pro-Rag 检索结果重排序工具：提供 RRF 融合、文件级聚合和智谱 Rerank 精排三种策略 */
@Slf4j
@Component
public class ProRagRerankUtil {

    @Value("${spring.ai.zhipuai.api-key}")
    private String apiKey;

    @Value("${pro-rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${pro-rag.models.rerank}")
    private String rerankModel;

    @Autowired
    private RetrievalProperties retrievalProperties;

    private final RestTemplate rerankRestTemplate;

    public ProRagRerankUtil() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.rerankRestTemplate = new RestTemplate(factory);
    }

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
        if (rerankEnabled) {
            try {
                List<FusedChunk> result = rerankFusionDetailed(vectorDocs, keywordDocs, query, topK);
                if (!result.isEmpty()) {
                    return result;
                }
                log.warn("Rerank 返回空结果，回退到 RRF 融合");
            } catch (Exception e) {
                log.warn("Rerank 调用失败，回退到 RRF 融合: {}", e.getMessage());
            }
        }
        return rrfFusionDetailed(vectorDocs, keywordDocs, topK);
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
     * 使用智谱 rerank 重排序
     */
    public List<String> rerankFusion(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs,
                                     String query, int topK) throws Exception {
        return rerankFusionDetailed(vectorDocs, keywordDocs, query, topK).stream()
                .map(FusedChunk::text)
                .collect(Collectors.toList());
    }

    /**
     * 智谱 rerank 的 detailed 版本：用入参索引回溯 docId，保证 prompt 顺序和 references 一致。
     */
    public List<FusedChunk> rerankFusionDetailed(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs,
                                                 String query, int topK) throws Exception {
        Map<String, String> idToContent = new LinkedHashMap<>();
        Map<String, Map<String, Object>> idToMeta = new LinkedHashMap<>();

        vectorDocs.forEach(doc -> {
            idToContent.putIfAbsent(doc.getId(), doc.getText());
            idToMeta.putIfAbsent(doc.getId(), doc.getMetadata());
        });

        keywordDocs.forEach(doc -> {
            idToContent.putIfAbsent(doc.getId(), doc.getContent());
            idToMeta.putIfAbsent(doc.getId(), doc.getMetadata());
        });

        List<String> orderedIds = new ArrayList<>(idToContent.keySet());
        List<String> documents = new ArrayList<>(idToContent.values());
        if (documents.isEmpty()) {
            log.info("没有检索到任何文档，无需重排序");
            return Collections.emptyList();
        }

        String url = "https://open.bigmodel.cn/api/paas/v4/rerank";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", rerankModel);
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", topK);
        requestBody.put("return_documents", true);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map> response = rerankRestTemplate.postForEntity(url, request, Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("重排序API调用失败: " + response.getStatusCode() + "，响应: " + response.getBody());
        }

        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("results")) {
            throw new RuntimeException("API响应格式异常，缺少results字段: " + responseBody);
        }

        List<Map<String, Object>> rerankedResults = (List<Map<String, Object>>) responseBody.get("results");
        if (rerankedResults == null || rerankedResults.isEmpty()) {
            log.warn("重排序返回空结果: {}", responseBody);
            return Collections.emptyList();
        }

        List<FusedChunk> result = new ArrayList<>();
        List<String> rankLogs = new ArrayList<>();

        for (int rank = 0; rank < rerankedResults.size(); rank++) {
            Map<String, Object> item = rerankedResults.get(rank);
            double score = item.containsKey("relevance_score")
                    ? ((Number) item.get("relevance_score")).doubleValue()
                    : 0.0;

            String docId = resolveRerankDocId(item, orderedIds, idToContent);
            if (docId == null) {
                continue;
            }

            String text = idToContent.get(docId);
            if (text == null) {
                continue;
            }
            result.add(new FusedChunk(docId, text, idToMeta.get(docId), score));
            rankLogs.add(String.format("排名 %d: docId=%s, 分数=%.4f", rank + 1, docId, score));
        }

        log.info("智谱rerank重排序结果：{}", String.join("; ", rankLogs));
        log.info("重排序后返回{}条文档，原始合并{}条", result.size(), documents.size());

        return result;
    }

    /**
     * 优先按 API 返回的 index 字段回溯 docId（最稳），失败才按内容精确匹配兜底。
     */
    private String resolveRerankDocId(Map<String, Object> rerankItem, List<String> orderedIds,
                                      Map<String, String> idToContent) {
        Object idxValue = rerankItem.get("index");
        if (idxValue instanceof Number numIdx) {
            int i = numIdx.intValue();
            if (i >= 0 && i < orderedIds.size()) {
                return orderedIds.get(i);
            }
        }
        Object docValue = rerankItem.get("document");
        if (docValue instanceof String text) {
            for (Map.Entry<String, String> entry : idToContent.entrySet()) {
                if (entry.getValue().equals(text)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
}
