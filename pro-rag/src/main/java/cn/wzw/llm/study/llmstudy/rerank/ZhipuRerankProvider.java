package cn.wzw.llm.study.llmstudy.rerank;

import cn.wzw.llm.study.llmstudy.util.ProRagRerankUtil.FusedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智谱 Rerank API 实现。
 * 将候选 chunk 文本提交到 open.bigmodel.cn 做语义精排。
 */
@Component
@Slf4j
public class ZhipuRerankProvider implements RerankProvider {

    @Value("${spring.ai.zhipuai.api-key}")
    private String apiKey;

    @Value("${pro-rag.models.rerank}")
    private String rerankModel;

    @Value("${pro-rag.rerank.base-url:https://open.bigmodel.cn/api/paas/v4/rerank}")
    private String rerankBaseUrl;

    private final RestTemplate restTemplate;

    public ZhipuRerankProvider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public List<FusedChunk> rerank(List<FusedChunk> candidates, String query, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 提取文本列表 + docId 映射
        List<String> orderedIds = new ArrayList<>(candidates.size());
        List<String> documents = new ArrayList<>(candidates.size());
        for (FusedChunk chunk : candidates) {
            orderedIds.add(chunk.docId());
            documents.add(chunk.text());
        }

        if (documents.isEmpty()) {
            log.info("没有检索到任何文档，无需重排序");
            return Collections.emptyList();
        }

        String url = rerankBaseUrl;
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
        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("重排序API调用失败: " + response.getStatusCode() + "，响应: " + response.getBody());
        }

        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("results")) {
            throw new RuntimeException("API响应格式异常，缺少results字段: " + responseBody);
        }

        @SuppressWarnings("unchecked")
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

            String docId = resolveDocId(item, orderedIds, candidates);
            if (docId == null) {
                continue;
            }

            // 从 candidates 中找回完整 chunk
            for (FusedChunk candidate : candidates) {
                if (candidate.docId().equals(docId)) {
                    result.add(new FusedChunk(candidate.docId(), candidate.text(),
                            candidate.metadata(), score));
                    rankLogs.add(String.format("排名 %d: docId=%s, 分数=%.4f", rank + 1, docId, score));
                    break;
                }
            }
        }

        log.info("智谱rerank重排序结果：{}", String.join("; ", rankLogs));
        log.info("重排序后返回{}条文档，原始候选{}条", result.size(), candidates.size());

        return result;
    }

    private String resolveDocId(Map<String, Object> rerankItem, List<String> orderedIds,
                                 List<FusedChunk> candidates) {
        Object idxValue = rerankItem.get("index");
        if (idxValue instanceof Number numIdx) {
            int i = numIdx.intValue();
            if (i >= 0 && i < orderedIds.size()) {
                return orderedIds.get(i);
            }
        }
        Object docValue = rerankItem.get("document");
        if (docValue instanceof String text) {
            for (FusedChunk candidate : candidates) {
                if (candidate.text().equals(text)) {
                    return candidate.docId();
                }
            }
        }
        return null;
    }
}
