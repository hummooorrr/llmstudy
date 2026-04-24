package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.config.RetrievalProperties;
import cn.wzw.llm.study.llmstudy.dto.locate.LocateHitSnippet;
import cn.wzw.llm.study.llmstudy.dto.locate.LocateResultItem;
import cn.wzw.llm.study.llmstudy.dto.retrieval.GenerationReferenceBundle;
import cn.wzw.llm.study.llmstudy.dto.retrieval.QueryBundle;
import cn.wzw.llm.study.llmstudy.dto.retrieval.ReferenceMaterial;
import cn.wzw.llm.study.llmstudy.model.ChunkMetadataKeys;
import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import cn.wzw.llm.study.llmstudy.util.ProRagRerankUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 统一封装定位和生成场景下的混合检索逻辑。
 * 对用户问题做轻量扩展后，再执行向量检索与关键词检索并去重融合。
 * <p>
 * 支持检索结果缓存（Caffeine 本地缓存）和 parent-child 上下文扩展。
 */
@Service
@Slf4j
public class ProRagRetrievalService {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ProRagElasticSearchService proRagElasticSearchService;

    @Autowired(required = false)
    private QuestionRewriteService questionRewriteService;

    @Autowired
    private ProRagRerankUtil proRagRerankUtil;

    @Autowired
    private RetrievalProperties retrievalProperties;

    @Autowired(required = false)
    private RetrievalCacheService retrievalCacheService;

    @Value("${pro-rag.retrieval.parent-context-enabled:true}")
    private boolean parentContextEnabled;

    /** 子 chunk 短于该字符数时才附加父 chunk 上下文，避免 prompt 冗余膨胀 */
    @Value("${pro-rag.retrieval.parent-context-child-max-chars:600}")
    private int parentContextChildMaxChars;

    /** 父 chunk 附加时的最大字符数，超出则截断 */
    @Value("${pro-rag.retrieval.parent-context-max-chars:1500}")
    private int parentContextMaxChars;

    public List<LocateResultItem> locateFiles(String query) throws Exception {
        String cacheKey = retrievalCacheService != null ? RetrievalCacheService.buildLocateKey(query) : null;
        if (cacheKey != null) {
            List<LocateResultItem> cached = retrievalCacheService.getLocate(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        QueryBundle bundle = buildQueries(query);
        RetrievalProperties.Locate locateCfg = retrievalProperties.getLocate();
        List<SearchHit> vectorHits = searchVectorHits(bundle.vectorQueries(), null,
                locateCfg.getVectorTopK(), locateCfg.getSimilarityThreshold());
        List<SearchHit> keywordHits = searchKeywordHits(bundle.keywordQueries(), locateCfg.getKeywordTopK());

        Map<String, FileLocateAccumulator> groupedFiles = new LinkedHashMap<>();
        vectorHits.forEach(hit -> groupedFiles
                .computeIfAbsent(hit.filePath(), key -> new FileLocateAccumulator(hit.filePath(), hit.filename()))
                .addHit(hit));
        keywordHits.forEach(hit -> groupedFiles
                .computeIfAbsent(hit.filePath(), key -> new FileLocateAccumulator(hit.filePath(), hit.filename()))
                .addHit(hit));

        List<LocateResultItem> result = groupedFiles.values().stream()
                .sorted(Comparator.comparingDouble(FileLocateAccumulator::score).reversed())
                .map(FileLocateAccumulator::toResult)
                .toList();

        if (cacheKey != null) {
            retrievalCacheService.putLocate(cacheKey, result);
        }

        return result;
    }

    public GenerationReferenceBundle retrieveReferenceBundle(String query, String directiveFilename) throws Exception {
        int finalTopK = retrievalProperties.getRerank().getFinalTopK();

        String cacheKey = retrievalCacheService != null
                ? RetrievalCacheService.buildRetrievalKey(query, directiveFilename,
                        retrievalProperties.getVectorTopK(),
                        retrievalProperties.getKeywordTopK(),
                        retrievalProperties.getSimilarityThreshold(),
                        finalTopK)
                : null;
        if (cacheKey != null) {
            GenerationReferenceBundle cached = retrievalCacheService.getRetrieval(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        GenerationReferenceBundle bundle = doRetrieveReferenceBundle(query, directiveFilename, finalTopK);

        if (cacheKey != null) {
            retrievalCacheService.putRetrieval(cacheKey, bundle);
        }

        return bundle;
    }

    private GenerationReferenceBundle doRetrieveReferenceBundle(String query, String directiveFilename, int finalTopK) throws Exception {
        QueryBundle bundle = buildQueries(query);
        List<Document> vectorDocs = searchVector(bundle.vectorQueries(), null,
                retrievalProperties.getVectorTopK(), retrievalProperties.getSimilarityThreshold());
        List<EsDocumentChunk> keywordDocs = searchKeyword(bundle.keywordQueries(), retrievalProperties.getKeywordTopK());

        if (StringUtils.hasText(directiveFilename)) {
            List<Document> directiveDocs = searchVector(
                    List.of(query),
                    directiveFilename.trim(),
                    retrievalProperties.getDirectiveVectorTopK(),
                    retrievalProperties.getDirectiveSimilarityThreshold()
            );
            mergeVectorDocs(vectorDocs, directiveDocs);
        }

        // 用 detailed 版本一次性拿到"融合后的顺序 + docId + metadata"，
        // 保证 contents 与 referenceMaterials 的条目数、排序严格一致，LLM 输出的 [^cN] 永远能命中前端卡片。
        List<ProRagRerankUtil.FusedChunk> fused = proRagRerankUtil.hybridFusionDetailed(
                vectorDocs, keywordDocs, bundle.originalQuery(), finalTopK);

        List<String> contents = new ArrayList<>(fused.size());
        List<ReferenceMaterial> referenceMaterials = new ArrayList<>(fused.size());
        int refIdx = 1;
        for (ProRagRerankUtil.FusedChunk chunk : fused) {
            String content = chunk.text();
            // Small-to-Big: 如果有父 chunk，附加上下文
            if (parentContextEnabled) {
                content = enrichWithParentContext(chunk, content);
            }
            contents.add(content);
            referenceMaterials.add(buildReferenceMaterial(chunk, refIdx, bundle.originalQuery()));
            refIdx++;
        }
        return new GenerationReferenceBundle(contents, referenceMaterials);
    }

    /**
     * Small-to-Big 检索：如果 chunk 有父 chunkId，从 ES 查父 chunk 全文做附加上下文。
     * 仅在子 chunk 足够短时才附加，且父文本会做长度截断，避免 prompt 体积失控。
     */
    private String enrichWithParentContext(ProRagRerankUtil.FusedChunk chunk, String originalText) {
        Map<String, Object> metadata = chunk.metadata();
        if (metadata == null) {
            return originalText;
        }
        // 子 chunk 已经足够长时，多半本身就包含主要语义，没必要再拼父块
        if (originalText != null && originalText.length() >= parentContextChildMaxChars) {
            return originalText;
        }
        Object parentId = metadata.get(ChunkMetadataKeys.PARENT_CHUNK_ID);
        if (parentId == null || !StringUtils.hasText(parentId.toString())) {
            return originalText;
        }
        try {
            Optional<EsDocumentChunk> parentChunk = proRagElasticSearchService.findById(parentId.toString());
            if (parentChunk.isPresent() && StringUtils.hasText(parentChunk.get().getContent())) {
                String parentText = parentChunk.get().getContent().trim();
                if (parentText.isEmpty() || originalText.contains(parentText)) {
                    return originalText;
                }
                String truncated = parentText.length() > parentContextMaxChars
                        ? parentText.substring(0, parentContextMaxChars) + "…"
                        : parentText;
                return originalText + "\n\n[上下文] " + truncated;
            }
        } catch (Exception e) {
            log.debug("查询父 chunk 失败 docId={} parentId={}: {}", chunk.docId(), parentId, e.getMessage());
        }
        return originalText;
    }

    private ReferenceMaterial buildReferenceMaterial(ProRagRerankUtil.FusedChunk chunk, int refIdx, String originalQuery) {
        Map<String, Object> metadata = chunk.metadata();
        String filePath = metadataValue(metadata, ChunkMetadataKeys.FILE_PATH, "unknown");
        String filename = metadataValue(metadata, ChunkMetadataKeys.FILENAME, "unknown");
        String chunkType = metadataValue(metadata, ChunkMetadataKeys.CHUNK_TYPE, "TEXT");
        Integer pageNumber = metadataInt(metadata, ChunkMetadataKeys.PAGE_NUMBER);
        String sectionPath = metadataOptional(metadata, ChunkMetadataKeys.SECTION_PATH);
        if (!StringUtils.hasText(sectionPath)) {
            sectionPath = resolveHeading(metadata);
        }
        String assetPath = metadataOptional(metadata, ChunkMetadataKeys.ASSET_PATH);
        return new ReferenceMaterial(
                "c" + refIdx,
                chunk.docId(),
                filePath,
                filename,
                extractSnippet(chunk.text(), originalQuery),
                Math.round(chunk.score() * 10000.0) / 10000.0,
                chunkType,
                pageNumber,
                sectionPath,
                assetPath
        );
    }

    private QueryBundle buildQueries(String query) {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query 不能为空");
        }

        String normalizedQuery = query.trim();

        if (questionRewriteService == null) {
            return new QueryBundle(normalizedQuery, List.of(normalizedQuery), List.of(normalizedQuery));
        }

        try {
            QuestionRewriteService.QueryRouteResult routeResult = questionRewriteService.routeQuery(normalizedQuery);

            return switch (routeResult.strategy()) {
                case DIRECT -> new QueryBundle(normalizedQuery, List.of(normalizedQuery), List.of(normalizedQuery));
                case DECOMPOSE -> {
                    LinkedHashSet<String> queries = new LinkedHashSet<>();
                    queries.add(normalizedQuery);
                    if (routeResult.subQueries() != null) {
                        routeResult.subQueries().stream()
                                .filter(StringUtils::hasText)
                                .map(String::trim)
                                .limit(retrievalProperties.getMaxRewriteQueries())
                                .forEach(queries::add);
                    }
                    List<String> queryList = new ArrayList<>(queries);
                    yield new QueryBundle(normalizedQuery, queryList, queryList);
                }
                case HYDE -> new QueryBundle(
                        normalizedQuery,
                        List.of(routeResult.hypotheticalAnswer()),
                        List.of(normalizedQuery)
                );
            };
        } catch (Exception e) {
            log.warn("查询路由失败，回退到原始查询: {}", normalizedQuery, e);
            return new QueryBundle(normalizedQuery, List.of(normalizedQuery), List.of(normalizedQuery));
        }
    }

    private List<Document> searchVector(List<String> queries, String filename, int topK, double threshold) {
        Map<String, Document> mergedDocs = new LinkedHashMap<>();
        for (String query : queries) {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold);

            if (StringUtils.hasText(filename)) {
                builder.filterExpression("filename == '" + escapeFilterValue(filename) + "'");
            }

            List<Document> docs = vectorStore.similaritySearch(builder.build());
            if (docs != null) {
                docs.forEach(doc -> mergedDocs.putIfAbsent(doc.getId(), doc));
            }
        }
        return new ArrayList<>(mergedDocs.values());
    }

    private List<EsDocumentChunk> searchKeyword(List<String> queries, int topK) throws Exception {
        Map<String, EsDocumentChunk> mergedDocs = new LinkedHashMap<>();
        for (String query : queries) {
            List<EsDocumentChunk> docs = proRagElasticSearchService.searchByKeyword(query, topK, true);
            if (docs != null) {
                docs.forEach(doc -> mergedDocs.putIfAbsent(doc.getId(), doc));
            }
        }
        return new ArrayList<>(mergedDocs.values());
    }

    private List<SearchHit> searchVectorHits(List<String> queries, String filename, int topK, double threshold) {
        List<SearchHit> hits = new ArrayList<>();
        for (String query : queries) {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold);
            if (StringUtils.hasText(filename)) {
                builder.filterExpression("filename == '" + escapeFilterValue(filename) + "'");
            }

            List<Document> docs = vectorStore.similaritySearch(builder.build());
            if (docs == null) {
                continue;
            }

            for (int i = 0; i < docs.size(); i++) {
                Document doc = docs.get(i);
                hits.add(new SearchHit(
                        doc.getId(),
                        metadataValue(doc.getMetadata(), ChunkMetadataKeys.FILE_PATH, "unknown"),
                        metadataValue(doc.getMetadata(), ChunkMetadataKeys.FILENAME, "unknown"),
                        doc.getText(),
                        "semantic",
                        query,
                        i + 1,
                        doc.getMetadata()
                ));
            }
        }
        return hits;
    }

    private List<SearchHit> searchKeywordHits(List<String> queries, int topK) throws Exception {
        List<SearchHit> hits = new ArrayList<>();
        for (String query : queries) {
            List<EsDocumentChunk> docs = proRagElasticSearchService.searchByKeyword(query, topK, true);
            if (docs == null) {
                continue;
            }
            for (int i = 0; i < docs.size(); i++) {
                EsDocumentChunk doc = docs.get(i);
                Map<String, Object> metadata = doc.getMetadata();
                hits.add(new SearchHit(
                        doc.getId(),
                        metadataValue(metadata, ChunkMetadataKeys.FILE_PATH, "unknown"),
                        metadataValue(metadata, ChunkMetadataKeys.FILENAME, "unknown"),
                        doc.getContent(),
                        "keyword",
                        query,
                        i + 1,
                        metadata
                ));
            }
        }
        return hits;
    }

    private void mergeVectorDocs(List<Document> baseDocs, List<Document> extraDocs) {
        if (extraDocs == null || extraDocs.isEmpty()) {
            return;
        }

        Map<String, Document> mergedDocs = new LinkedHashMap<>();
        if (baseDocs != null) {
            baseDocs.forEach(doc -> mergedDocs.putIfAbsent(doc.getId(), doc));
        }
        extraDocs.forEach(doc -> mergedDocs.putIfAbsent(doc.getId(), doc));

        baseDocs.clear();
        baseDocs.addAll(mergedDocs.values());
    }

    private String extractSnippet(String content, String matchedQuery) {
        if (!StringUtils.hasText(content)) {
            return "";
        }

        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }

        for (String candidate : buildSnippetCandidates(matchedQuery)) {
            int index = normalized.toLowerCase(Locale.ROOT).indexOf(candidate.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                int start = Math.max(0, index - 24);
                int end = Math.min(normalized.length(), index + candidate.length() + 48);
                String snippet = normalized.substring(start, end).trim();
                if (start > 0) {
                    snippet = "..." + snippet;
                }
                if (end < normalized.length()) {
                    snippet = snippet + "...";
                }
                return snippet;
            }
        }

        return normalized.substring(0, 120).trim() + "...";
    }

    private List<String> buildSnippetCandidates(String query) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (StringUtils.hasText(query)) {
            String trimmed = query.trim();
            candidates.add(trimmed);
            for (String token : trimmed.split("[,，。；;、\\s]+")) {
                if (token.length() >= 2) {
                    candidates.add(token);
                }
            }
        }
        return new ArrayList<>(candidates);
    }

    private String metadataValue(Map<String, Object> metadata, String key, String defaultValue) {
        if (metadata == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private String metadataOptional(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private Integer metadataInt(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String resolveHeading(Map<String, Object> metadata) {
        if (metadata == null) {
            return "";
        }

        List<String> headings = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Object heading = metadata.get("heading" + i);
            if (heading != null && StringUtils.hasText(heading.toString())) {
                headings.add(heading.toString());
            }
        }
        return String.join(" / ", headings);
    }

    private String escapeFilterValue(String value) {
        // filename 只允许字母、数字、中文、点、下划线、连字符和空格
        if (!value.matches("^[\\w\\u4e00-\\u9fa5.\\-\\s ()（）]+$")) {
            throw new IllegalArgumentException("不合法的文件名过滤值: " + value);
        }
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private record SearchHit(
            String id,
            String filePath,
            String filename,
            String content,
            String source,
            String matchedQuery,
            int rank,
            Map<String, Object> metadata
    ) {
        double score(int rrfK) {
            return 1.0 / (rrfK + rank);
        }
    }

    private final class FileLocateAccumulator {
        private final String filePath;
        private final String filename;
        private double score;
        private final LinkedHashSet<String> chunkIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> matchedQueries = new LinkedHashSet<>();
        private final LinkedHashSet<String> hitReasons = new LinkedHashSet<>();
        private final List<LocateHitSnippet> hitSnippets = new ArrayList<>();
        private boolean semanticHit;
        private boolean keywordHit;

        private FileLocateAccumulator(String filePath, String filename) {
            this.filePath = filePath;
            this.filename = filename;
        }

        private void addHit(SearchHit hit) {
            int rrfK = retrievalProperties.getRerank().getRrfK();
            score += hit.score(rrfK);
            chunkIds.add(hit.id());
            if (StringUtils.hasText(hit.matchedQuery())) {
                matchedQueries.add(hit.matchedQuery());
            }

            String heading = resolveHeading(hit.metadata());
            String baseReason = switch (hit.source()) {
                case "semantic" -> StringUtils.hasText(heading)
                        ? "语义检索命中，重点落在「" + heading + "」相关内容"
                        : "语义检索命中，文档内容与问题表达高度相似";
                case "keyword" -> StringUtils.hasText(heading)
                        ? "关键词检索命中，在「" + heading + "」相关段落直接匹配"
                        : "关键词检索命中，问题中的关键信息与文档原文直接匹配";
                default -> "检索命中";
            };

            if ("semantic".equals(hit.source())) {
                semanticHit = true;
            }
            if ("keyword".equals(hit.source())) {
                keywordHit = true;
            }

            hitReasons.add(baseReason);
            if (semanticHit && keywordHit) {
                hitReasons.add("同时命中语义检索和关键词检索，可信度更高");
            }
            if (matchedQueries.size() >= 2) {
                hitReasons.add("多个查询改写都能命中这份材料，主题相关性较强");
            }

            String snippet = extractSnippet(hit.content(), hit.matchedQuery());
            if (StringUtils.hasText(snippet) && hitSnippets.stream().noneMatch(existing -> Objects.equals(existing.snippet(), snippet))) {
                hitSnippets.add(new LocateHitSnippet(snippet, hit.source(), hit.matchedQuery(), baseReason));
            }
        }

        private double score() {
            return score;
        }

        private LocateResultItem toResult() {
            List<LocateHitSnippet> snippets = hitSnippets.stream().limit(retrievalProperties.getLocate().getSnippetLimit()).toList();
            return new LocateResultItem(
                    filePath,
                    filename,
                    Math.round(score * 10000.0) / 10000.0,
                    chunkIds.size(),
                    new ArrayList<>(matchedQueries),
                    new ArrayList<>(hitReasons),
                    snippets
            );
        }
    }
}
