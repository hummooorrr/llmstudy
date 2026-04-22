package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pro-Rag ElasticSearch 服务：索引管理、批量写入、按文件名查询删除和中文关键词检索 */
@Service
@Slf4j
public class ProRagElasticSearchService {

    @Autowired
    private ElasticsearchClient client;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String INDEX_NAME = "pro_rag_docs";

    private static final String FIELD_CONTENT = "content";

    @PostConstruct
    public void init() {
        try {
            if (!indexExists(INDEX_NAME)) {
                createIndex();
                log.info("ES index [{}] created with IK analyzer!", INDEX_NAME);
            } else {
                log.info("ES index [{}] already exists, skip creation. Attempting mapping upgrade for metadata fields.", INDEX_NAME);
                ensureMetadataMapping();
            }
        } catch (Exception e) {
            log.error("Failed to initialise ES index: {}", e.getMessage(), e);
        }
    }

    /**
     * 创建索引（IK 分词 + 停用词 + lowercase + filePath/filename 映射 + 结构化 metadata 字段）
     */
    public void createIndex() throws Exception {
        String settingsAndMappingJson =
                "{"
                        + "\"settings\": {"
                        + "\"number_of_shards\": 1,"
                        + "\"number_of_replicas\": 0,"
                        + "\"analysis\": {"
                        + "\"filter\": {"
                        + "\"my_stop_filter\": {"
                        + "\"type\": \"stop\","
                        + "\"stopwords\": \"_chinese_\""
                        + "}"
                        + "},"
                        + "\"analyzer\": {"
                        + "\"ik_max\": {"
                        + "\"type\": \"custom\","
                        + "\"tokenizer\": \"ik_max_word\","
                        + "\"filter\": [\"lowercase\", \"my_stop_filter\"]"
                        + "},"
                        + "\"ik_smart\": {"
                        + "\"type\": \"custom\","
                        + "\"tokenizer\": \"ik_smart\","
                        + "\"filter\": [\"lowercase\", \"my_stop_filter\"]"
                        + "}"
                        + "}"
                        + "}"
                        + "},"
                        + "\"mappings\": {"
                        + "\"properties\": {"
                        + "\"id\": { \"type\": \"keyword\" },"
                        + "\"content\": {"
                        + "\"type\": \"text\","
                        + "\"analyzer\": \"ik_max\","
                        + "\"search_analyzer\": \"ik_smart\","
                        + "\"fields\": {"
                        + "\"smart\": {"
                        + "\"type\": \"text\","
                        + "\"analyzer\": \"ik_smart\","
                        + "\"search_analyzer\": \"ik_smart\""
                        + "}"
                        + "}"
                        + "},"
                        + "\"metadata\": {"
                        + "\"type\": \"object\","
                        + "\"properties\": "
                        + metadataPropertiesJson()
                        + "}"
                        + "}"
                        + "}"
                        + "}";

        CreateIndexRequest request = CreateIndexRequest.of(b -> b
                .index(INDEX_NAME)
                .withJson(new StringReader(settingsAndMappingJson))
        );

        client.indices().create(request);
    }

    /**
     * 增量为已有索引追加新的 metadata 字段（chunkType / pageNumber / sectionPath / assetPath / chunkProfile 等）。
     * 仅做字段追加，不会修改已有字段类型。
     */
    public void ensureMetadataMapping() {
        try {
            String mappingJson = "{\"properties\":{\"metadata\":{\"properties\":"
                    + metadataPropertiesJson() + "}}}";
            PutMappingRequest request = PutMappingRequest.of(b -> b
                    .index(INDEX_NAME)
                    .withJson(new StringReader(mappingJson))
            );
            client.indices().putMapping(request);
            log.info("ES index [{}] metadata mapping ensured.", INDEX_NAME);
        } catch (Exception e) {
            log.warn("ES metadata mapping 追加失败（可能是字段类型冲突，建议新环境 reindex）: {}", e.getMessage());
        }
    }

    private String metadataPropertiesJson() {
        return "{"
                + "\"source\": { \"type\": \"keyword\" },"
                + "\"category\": { \"type\": \"keyword\" },"
                + "\"orderId\": { \"type\": \"keyword\" },"
                + "\"filePath\": { \"type\": \"keyword\" },"
                + "\"filename\": { \"type\": \"keyword\" },"
                + "\"chunkType\": { \"type\": \"keyword\" },"
                + "\"pageNumber\": { \"type\": \"integer\" },"
                + "\"sectionPath\": { \"type\": \"keyword\" },"
                + "\"assetPath\": { \"type\": \"keyword\" },"
                + "\"assetDescription\": { \"type\": \"text\", \"analyzer\": \"ik_smart\" },"
                + "\"chunkProfile\": { \"type\": \"keyword\" },"
                + "\"chunkSize\": { \"type\": \"integer\" },"
                + "\"chunkOverlap\": { \"type\": \"integer\" },"
                + "\"sourceType\": { \"type\": \"keyword\" }"
                + "}";
    }

    /**
     * 批量存储
     */
    public void bulkIndex(List<EsDocumentChunk> docs) throws Exception {
        if (docs == null || docs.isEmpty()) {
            return;
        }

        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

        for (EsDocumentChunk doc : docs) {
            bulkBuilder.operations(op -> op
                    .index(idx -> idx
                            .index(INDEX_NAME)
                            .id(doc.getId())
                            .document(doc)
                    )
            );
        }

        bulkBuilder.refresh(Refresh.True);

        BulkResponse response = client.bulk(bulkBuilder.build());
        if (response.errors()) {
            log.error("Bulk indexing completed with failures");
            response.items().forEach(item -> {
                if (item.error() != null) {
                    log.error("Failed to index doc {}: {}", item.id(), item.error().reason());
                }
            });
        } else {
            log.info("Successfully indexed {} documents", docs.size());
        }
    }

    public boolean indexExists(String indexName) throws IOException {
        ExistsRequest request = ExistsRequest.of(b -> b.index(indexName));
        return client.indices().exists(request).value();
    }

    /**
     * 按 filename 查询 ES 中已有的旧数据 _id 列表
     */
    public List<String> findIdsByFilename(String filename) throws Exception {
        SearchRequest request = SearchRequest.of(b -> b
                .index(INDEX_NAME)
                .query(q -> q.term(t -> t.field("metadata.filename").value(filename)))
                .size(10000)
                .source(src -> src.fetch(false))
        );
        SearchResponse<Void> response = client.search(request, Void.class);
        return response.hits().hits().stream()
                .map(hit -> hit.id())
                .toList();
    }

    /**
     * 按 _id 列表删除 ES 数据
     */
    public void deleteByIds(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        DeleteByQueryRequest request = DeleteByQueryRequest.of(b -> b
                .index(INDEX_NAME)
                .query(q -> q.ids(i -> i.values(ids)))
        );
        DeleteByQueryResponse response = client.deleteByQuery(request);
        log.info("ES 删除 {} 条数据", response.deleted());
    }

    /**
     * 中文检索 - ik_max_word 建库 + ik_smart 检索
     */
    public List<EsDocumentChunk> searchByKeyword(String keyword) throws Exception {
        return searchByKeyword(keyword, 5, false);
    }

    /**
     * 中文检索：ik_max_word / ik_smart 切换
     */
    public List<EsDocumentChunk> searchByKeyword(String keyword, int size, boolean useSmartAnalyzer) throws Exception {
        String field = useSmartAnalyzer ? FIELD_CONTENT + ".smart" : FIELD_CONTENT;

        SearchRequest request = SearchRequest.of(b -> b
                .index(INDEX_NAME)
                .query(q -> q
                        .match(m -> m
                                .field(field)
                                .query(keyword)
                        )
                )
                .size(size)
        );

        SearchResponse<EsDocumentChunk> response = client.search(request, EsDocumentChunk.class);

        List<EsDocumentChunk> result = new ArrayList<>();
        response.hits().hits().forEach(hit -> {
            if (hit.source() != null) {
                EsDocumentChunk chunk = hit.source();
                if (chunk.getId() == null) {
                    chunk.setId(hit.id());
                }
                result.add(chunk);
            }
        });

        return result;
    }

    /**
     * 按 _id 精确查询单个 chunk，用于前端点击引用角标查看原文上下文。
     */
    public Optional<EsDocumentChunk> findById(String id) throws Exception {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        GetResponse<EsDocumentChunk> response = client.get(g -> g.index(INDEX_NAME).id(id), EsDocumentChunk.class);
        if (!response.found() || response.source() == null) {
            return Optional.empty();
        }
        EsDocumentChunk chunk = response.source();
        if (chunk.getId() == null) {
            chunk.setId(response.id());
        }
        return Optional.of(chunk);
    }

    /**
     * 找同一文件内、同一页码下的相邻 chunk，作为预览时的上下文。
     * 结果按 chunkProfile + chunkSize 的"自然顺序"大致还原入库顺序。
     */
    public List<EsDocumentChunk> findSiblings(String filename, Integer pageNumber, int limit) throws Exception {
        if (filename == null || filename.isBlank()) {
            return List.of();
        }
        SearchRequest request = SearchRequest.of(b -> {
            b.index(INDEX_NAME).size(Math.max(1, limit));
            b.query(q -> q.bool(bool -> {
                bool.must(m -> m.term(t -> t.field("metadata.filename").value(filename)));
                if (pageNumber != null) {
                    bool.must(m -> m.term(t -> t.field("metadata.pageNumber").value(pageNumber)));
                }
                return bool;
            }));
            b.sort(s -> s.field(f -> f.field("metadata.pageNumber").order(SortOrder.Asc)));
            return b;
        });

        SearchResponse<EsDocumentChunk> response = client.search(request, EsDocumentChunk.class);
        List<EsDocumentChunk> result = new ArrayList<>();
        response.hits().hits().forEach(hit -> {
            if (hit.source() != null) {
                EsDocumentChunk chunk = hit.source();
                if (chunk.getId() == null) {
                    chunk.setId(hit.id());
                }
                result.add(chunk);
            }
        });
        return result;
    }

    /**
     * 删除整个索引并按最新 mapping 重建。调用方需要额外重新入库已有文件。
     */
    public void recreateIndex() throws Exception {
        if (indexExists(INDEX_NAME)) {
            client.indices().delete(d -> d.index(INDEX_NAME));
            log.warn("ES index [{}] deleted for recreation.", INDEX_NAME);
        }
        createIndex();
        log.info("ES index [{}] recreated with latest mapping.", INDEX_NAME);
    }
}
