package cn.wzw.llm.study.llmstudy;


import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class ProRagRerankUtil {

    /**
     * RRF 算法融合向量检索和关键词检索结果
     */
    public static List<String> rrfFusion(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs, int topK) {
        final int K = 60;
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, String> idToChunkId = new HashMap<>();

        for (int i = 0; i < vectorDocs.size(); i++) {
            Document doc = vectorDocs.get(i);
            String docId = doc.getId();
            String chunkId = doc.getMetadata().getOrDefault("chunkId", "unknown").toString();
            idToChunkId.put(docId, chunkId);
            int rank = i + 1;
            double score = 1.0 / (K + rank);
            rrfScores.put(docId, rrfScores.getOrDefault(docId, 0.0) + score);
        }

        for (int i = 0; i < keywordDocs.size(); i++) {
            EsDocumentChunk doc = keywordDocs.get(i);
            String docId = doc.getId();
            String chunkId = doc.getMetadata().getOrDefault("chunkId", "unknown").toString();
            idToChunkId.put(docId, chunkId);
            int rank = i + 1;
            double score = 1.0 / (K + rank);
            rrfScores.put(docId, rrfScores.getOrDefault(docId, 0.0) + score);
        }

        List<String> sortedDocIds = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(topK)
                .collect(Collectors.toList());

        String scoresLog = sortedDocIds.stream()
                .map(docId -> {
                    String chunkId = idToChunkId.getOrDefault(docId, "unknown");
                    double score = rrfScores.getOrDefault(docId, 0.0);
                    return String.format("chunkId: %s, RRF Score: %.4f", chunkId, score);
                })
                .collect(Collectors.joining("; "));

        log.info("RRF融合后top{}结果：{}", topK, scoresLog);

        Map<String, String> idToContent = new HashMap<>();
        vectorDocs.forEach(doc -> idToContent.putIfAbsent(doc.getId(), doc.getText()));
        keywordDocs.forEach(doc -> idToContent.putIfAbsent(doc.getId(), doc.getContent()));

        return sortedDocIds.stream()
                .map(idToContent::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * RRF融合后按filePath聚合，返回文件级别的相关度排序
     */
    public static List<Map<String, Object>> rrfFusionGroupByFilePath(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs) {
        final int K = 60;
        // filePath -> 聚合得分
        Map<String, Double> filePathScores = new LinkedHashMap<>();
        // filePath -> filename
        Map<String, String> filePathToFilename = new LinkedHashMap<>();
        // filePath -> 匹配的chunk数
        Map<String, Integer> filePathChunkCount = new LinkedHashMap<>();

        // 处理向量检索结果
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

        // 处理关键词检索结果
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

        // 按聚合得分降序排列
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
    public static List<String> rerankFusion(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs, String query, int topK) throws Exception {
        Map<String, String> idToContent = new LinkedHashMap<>();
        Map<String, String> idToChunkId = new HashMap<>();

        vectorDocs.forEach(doc -> {
            String docId = doc.getId();
            idToContent.putIfAbsent(docId, doc.getText());
            String chunkId = doc.getMetadata().getOrDefault("chunkId", docId).toString();
            idToChunkId.putIfAbsent(docId, chunkId);
        });

        keywordDocs.forEach(doc -> {
            String docId = doc.getId();
            idToContent.putIfAbsent(docId, doc.getContent());
            String chunkId = doc.getMetadata().getOrDefault("chunkId", docId).toString();
            idToChunkId.putIfAbsent(docId, chunkId);
        });

        List<String> documents = new ArrayList<>(idToContent.values());
        if (documents.isEmpty()) {
            log.info("没有检索到任何文档，无需重排序");
            return Collections.emptyList();
        }

        String url = "https://open.bigmodel.cn/api/paas/v4/rerank";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + resolveZhipuApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "bge-reranker-v2-m3");
        requestBody.put("query", query);
        requestBody.put("documents", documents);
        requestBody.put("top_n", topK);
        requestBody.put("return_documents", true);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setRequestFactory(new SimpleClientHttpRequestFactory() {{
            setConnectTimeout(5000);
            setReadTimeout(10000);
        }});

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("重排序API调用失败: " + response.getStatusCode() + "，响应: " + response.getBody());
        }

        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("data")) {
            throw new RuntimeException("API响应格式异常，缺少data字段: " + responseBody);
        }

        List<Map<String, Object>> rerankedResults = (List<Map<String, Object>>) responseBody.get("data");
        if (rerankedResults == null || rerankedResults.isEmpty()) {
            log.warn("重排序返回空结果: {}", responseBody);
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        List<String> rankLogs = new ArrayList<>();

        for (int i = 0; i < rerankedResults.size(); i++) {
            Map<String, Object> item = rerankedResults.get(i);
            Double score = item.containsKey("relevance_score")
                    ? ((Number) item.get("relevance_score")).doubleValue()
                    : 0.0;
            String text = (String) item.get("document");

            if (text != null) {
                result.add(text);

                String matchedChunkId = "unknown";
                for (Map.Entry<String, String> entry : idToContent.entrySet()) {
                    if (entry.getValue().equals(text)) {
                        matchedChunkId = idToChunkId.getOrDefault(entry.getKey(), "unknown");
                        break;
                    }
                }

                rankLogs.add(String.format("排名 %d: chunkId=%s, 分数=%.4f",
                        i + 1, matchedChunkId, score));
            }
        }

        log.info("智谱rerank重排序结果：{}", String.join("; ", rankLogs));
        log.info("重排序后返回{}条文档，原始合并{}条", result.size(), documents.size());

        return result;
    }

    private static String resolveZhipuApiKey() {
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置环境变量 ZHIPU_API_KEY，无法调用智谱 rerank");
        }
        return apiKey;
    }
}
