package cn.wzw.llm.study.llmstudy;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class EmbeddingService {

    /**
     * 向量库写入批量大小
     * 部分VectorStore实现（如PGVector）对单次写入的文档数量有限制，9是经验安全值
     */
    private static final int BATCH_SIZE = 9;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    /**
     * 向量化
     */
    public List<float[]> embed(List<Document> documents) {
        return documents.stream().map(document -> embeddingModel.embed(document.getText())).collect(Collectors.toList());
    }

    /**
     * 存储向量库（分批写入）
     */
    public void embedAndStore(List<Document> documents) {
        for (int i = 0; i < documents.size(); i += BATCH_SIZE) {
            // subList返回原List的视图，部分VectorStore实现不支持视图操作，包一层ArrayList确保安全
            List<Document> batch = new ArrayList<>(documents.subList(i, Math.min(i + BATCH_SIZE, documents.size())));
            vectorStore.add(batch);
        }
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
