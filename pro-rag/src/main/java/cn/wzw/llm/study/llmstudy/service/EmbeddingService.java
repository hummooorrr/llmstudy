package cn.wzw.llm.study.llmstudy.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
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
        // 过滤空文本文档：ZhiPu embedding API 对数组中的空字符串/null 元素返回 error 1210
        List<Document> validDocuments = documents.stream()
                .filter(doc -> doc.getText() != null && !doc.getText().isBlank())
                .toList();
        if (validDocuments.isEmpty()) {
            log.warn("所有文档文本为空，跳过嵌入入库");
            return;
        }
        if (validDocuments.size() < documents.size()) {
            log.warn("过滤掉 {} 个空文本文档（共 {} 个）",
                    documents.size() - validDocuments.size(), documents.size());
        }
        int totalBatches = (validDocuments.size() + BATCH_SIZE - 1) / BATCH_SIZE;
        log.info("[嵌入] 开始: 有效文档={}, 总批次={}", validDocuments.size(), totalBatches);
        for (int i = 0; i < validDocuments.size(); i += BATCH_SIZE) {
            int batchIndex = i / BATCH_SIZE + 1;
            List<Document> batch = new ArrayList<>(validDocuments.subList(i, Math.min(i + BATCH_SIZE, validDocuments.size())));
            // 记录本批每个文档文本长度，便于排查空/超长文本
            log.debug("[嵌入] 第 {}/{} 批: 文档数={}, 文本长度范围=[{}-{}]",
                    batchIndex, totalBatches, batch.size(),
                    batch.stream().mapToInt(d -> d.getText().length()).min().orElse(0),
                    batch.stream().mapToInt(d -> d.getText().length()).max().orElse(0));
            long batchStart = System.currentTimeMillis();
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    vectorStore.add(batch);
                    log.info("[嵌入] 第 {}/{} 批完成: 文档数={}, 耗时={}ms",
                            batchIndex, totalBatches, batch.size(), System.currentTimeMillis() - batchStart);
                    break;
                } catch (Exception e) {
                    log.warn("[嵌入] 第 {}/{} 批失败（尝试 {}/{}）: 耗时={}ms, 错误={}",
                            batchIndex, totalBatches, attempt, MAX_RETRIES,
                            System.currentTimeMillis() - batchStart, e.getMessage());
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

}
