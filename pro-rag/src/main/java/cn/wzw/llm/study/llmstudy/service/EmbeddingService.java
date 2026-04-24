package cn.wzw.llm.study.llmstudy.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** 向量嵌入服务：文档向量化、批量入库、按文件名查询和相似度检索 */
@Service
@Slf4j
public class EmbeddingService {

    /**
     * 向量库写入批量大小
     * 部分VectorStore实现（如PGVector）对单次写入的文档数量有限制，9是经验安全值
     */
    private static final int BATCH_SIZE = 9;

    private static final int MAX_RETRIES = 3;

    @Value("${pro-rag.embedding.retry-interval-base:1000}")
    private long retryIntervalBase;

    @Value("${spring.ai.vectorstore.pgvector.table-name:vector_store}")
    private String tableName;

    @Autowired
    @Qualifier("zhiPuAiEmbeddingModel")
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 向量化
     */
    public List<float[]> embed(List<Document> documents) {
        return documents.stream().map(document -> embeddingModel.embed(document.getText())).collect(Collectors.toList());
    }

    /**
     * 存储向量库（分批写入，带重试）
     */
    public void embedAndStore(List<Document> documents) {
        int totalBatches = (documents.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        for (int i = 0; i < documents.size(); i += BATCH_SIZE) {
            int batchIndex = i / BATCH_SIZE + 1;
            List<Document> batch = new ArrayList<>(documents.subList(i, Math.min(i + BATCH_SIZE, documents.size())));
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    vectorStore.add(batch);
                    break;
                } catch (Exception e) {
                    log.warn("嵌入第 {}/{} 批失败（尝试 {}/{}）: {}", batchIndex, totalBatches, attempt, MAX_RETRIES, e.getMessage());
                    if (attempt == MAX_RETRIES) {
                        throw new RuntimeException("嵌入失败，已重试" + MAX_RETRIES + "次", e);
                    }
                    try {
                        Thread.sleep(retryIntervalBase * attempt);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /**
     * 按 filename 查询向量库中已有的旧数据 ID
     */
    public List<String> findIdsByFilename(String filename) {
        if (!tableName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("非法表名: " + tableName);
        }
        return jdbcTemplate.queryForList(
                "SELECT id FROM " + tableName + " WHERE metadata->>'filename' = ?",
                String.class, filename
        );
    }

    /**
     * 按 ID 列表删除向量库数据
     */
    public void deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        vectorStore.delete(ids);
        log.info("向量库删除 {} 条数据", ids.size());
    }

    /**
     * 相似度查询
     * @param query 用户的原始问题
     * @return 文档块
     */
    public List<Document> similarSearch(String query) {
        return vectorStore.similaritySearch(SearchRequest
                .builder()
                .query(query)
                .topK(5)
                .similarityThreshold(0.7f)
                .build());
    }
}
