package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.model.ChunkMetadataKeys;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                + "\"chunkId\": { \"type\": \"keyword\" },"
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
     * 按文件名/页码回查候选 chunk，用于引用 ID 不一致时的兜底定位。
     */
    public List<EsDocumentChunk> findCandidates(String filename, Integer pageNumber, int limit) throws Exception {
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
            // 没有 pageNumber 字段的场景（如 Markdown），声明 unmappedType 避免触发 all shards failed
            b.sort(s -> s.field(f -> f
                    .field("metadata.pageNumber")
                    .order(SortOrder.Asc)
                    .unmappedType(co.elastic.clients.elasticsearch._types.mapping.FieldType.Integer)
            ));
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
     * 为当前 chunk 找更精确的上下文片段。
     * 优先同页/同章节/父子关系命中，拿不到足够线索时宁缺毋滥，避免展示同文件里的无关片段。
     */
    public List<EsDocumentChunk> findSiblings(EsDocumentChunk target, int limit) {
        if (target == null || limit <= 0) {
            return List.of();
        }
        String filename = metadataString(target, ChunkMetadataKeys.FILENAME);
        Integer pageNumber = metadataInteger(target, ChunkMetadataKeys.PAGE_NUMBER);
        if (filename == null || filename.isBlank()) {
            return List.of();
        }

        int candidateLimit = pageNumber != null ? Math.max(limit * 12, 60) : Math.max(limit * 20, 200);
        List<EsDocumentChunk> candidates;
        try {
            candidates = findCandidates(filename, pageNumber, candidateLimit);
        } catch (Exception e) {
            log.warn("查询同级 chunk 失败，降级返回空：filename={}, page={}, msg={}", filename, pageNumber, e.getMessage());
            return List.of();
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        return candidates.stream()
                .filter(candidate -> !sameChunk(target, candidate))
                .map(candidate -> Map.entry(candidate, scoreSibling(target, candidate)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Comparator.<Map.Entry<EsDocumentChunk, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(entry -> metadataInteger(entry.getKey(), ChunkMetadataKeys.PAGE_NUMBER),
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(entry -> entry.getKey().getId(), Comparator.nullsLast(String::compareTo)))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private int scoreSibling(EsDocumentChunk target, EsDocumentChunk candidate) {
        int score = 0;

        String targetChunkId = effectiveChunkId(target);
        String candidateChunkId = effectiveChunkId(candidate);
        String targetParentId = metadataString(target, ChunkMetadataKeys.PARENT_CHUNK_ID);
        String candidateParentId = metadataString(candidate, ChunkMetadataKeys.PARENT_CHUNK_ID);

        if (targetChunkId != null && targetChunkId.equals(candidateParentId)) {
            score += 1200;
        }
        if (candidateChunkId != null && candidateChunkId.equals(targetParentId)) {
            score += 1200;
        }
        if (targetParentId != null && targetParentId.equals(candidateParentId)) {
            score += 900;
        }
        if (containsChildChunkId(target, candidateChunkId) || containsChildChunkId(candidate, targetChunkId)) {
            score += 1000;
        }

        Integer targetPage = metadataInteger(target, ChunkMetadataKeys.PAGE_NUMBER);
        Integer candidatePage = metadataInteger(candidate, ChunkMetadataKeys.PAGE_NUMBER);
        if (targetPage != null && candidatePage != null) {
            if (targetPage.equals(candidatePage)) {
                score += 700;
            } else {
                int delta = Math.abs(targetPage - candidatePage);
                if (delta == 1) {
                    score += 160;
                } else if (delta == 2) {
                    score += 80;
                }
            }
        }

        String targetSection = metadataString(target, ChunkMetadataKeys.SECTION_PATH);
        String candidateSection = metadataString(candidate, ChunkMetadataKeys.SECTION_PATH);
        if (sameNormalized(targetSection, candidateSection)) {
            score += 520;
        }

        String targetType = metadataString(target, ChunkMetadataKeys.CHUNK_TYPE);
        String candidateType = metadataString(candidate, ChunkMetadataKeys.CHUNK_TYPE);
        if (sameNormalized(targetType, candidateType)) {
            score += 240;
        }

        String targetProfile = metadataString(target, ChunkMetadataKeys.CHUNK_PROFILE);
        String candidateProfile = metadataString(candidate, ChunkMetadataKeys.CHUNK_PROFILE);
        if (sameNormalized(targetProfile, candidateProfile)) {
            score += 120;
        }

        String targetSourceType = metadataString(target, ChunkMetadataKeys.SOURCE_TYPE);
        String candidateSourceType = metadataString(candidate, ChunkMetadataKeys.SOURCE_TYPE);
        if (sameNormalized(targetSourceType, candidateSourceType)) {
            score += 80;
        }

        if (sharedContentHint(target, candidate)) {
            score += 60;
        }
        return score;
    }

    private boolean sameChunk(EsDocumentChunk left, EsDocumentChunk right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.getId() != null && left.getId().equals(right.getId())) {
            return true;
        }
        String leftChunkId = effectiveChunkId(left);
        String rightChunkId = effectiveChunkId(right);
        return leftChunkId != null && leftChunkId.equals(rightChunkId);
    }

    private String effectiveChunkId(EsDocumentChunk chunk) {
        if (chunk == null) {
            return null;
        }
        String metadataChunkId = metadataString(chunk, ChunkMetadataKeys.CHUNK_ID);
        if (metadataChunkId != null && !metadataChunkId.isBlank()) {
            return metadataChunkId;
        }
        return chunk.getId();
    }

    private boolean containsChildChunkId(EsDocumentChunk chunk, String childChunkId) {
        if (chunk == null || childChunkId == null || childChunkId.isBlank()) {
            return false;
        }
        Map<String, Object> metadata = chunk.getMetadata();
        if (metadata == null) {
            return false;
        }
        Object childChunkIds = metadata.get(ChunkMetadataKeys.CHILD_CHUNK_IDS);
        if (!(childChunkIds instanceof List<?> list)) {
            return false;
        }
        return list.stream().filter(item -> item != null).map(Object::toString).anyMatch(childChunkId::equals);
    }

    private String metadataString(EsDocumentChunk chunk, String key) {
        if (chunk == null || chunk.getMetadata() == null) {
            return null;
        }
        Object value = chunk.getMetadata().get(key);
        return value == null ? null : value.toString();
    }

    private Integer metadataInteger(EsDocumentChunk chunk, String key) {
        if (chunk == null || chunk.getMetadata() == null) {
            return null;
        }
        Object value = chunk.getMetadata().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean sameNormalized(String left, String right) {
        return normalize(left).equals(normalize(right)) && !normalize(left).isEmpty();
    }

    private String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private boolean sharedContentHint(EsDocumentChunk target, EsDocumentChunk candidate) {
        String targetText = normalize(target == null ? null : target.getContent());
        String candidateText = normalize(candidate == null ? null : candidate.getContent());
        if (targetText.isEmpty() || candidateText.isEmpty()) {
            return false;
        }
        String prefix = targetText.substring(0, Math.min(24, targetText.length()));
        return prefix.length() >= 8 && candidateText.contains(prefix);
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
