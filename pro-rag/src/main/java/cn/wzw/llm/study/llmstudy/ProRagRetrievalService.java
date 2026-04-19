package cn.wzw.llm.study.llmstudy;

import cn.wzw.llm.study.llmstudy.dto.locate.LocateHitSnippet;
import cn.wzw.llm.study.llmstudy.dto.locate.LocateResultItem;
import cn.wzw.llm.study.llmstudy.dto.retrieval.GenerationReferenceBundle;
import cn.wzw.llm.study.llmstudy.dto.retrieval.QueryBundle;
import cn.wzw.llm.study.llmstudy.dto.retrieval.ReferenceMaterial;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * 统一封装定位和生成场景下的混合检索逻辑。
 * 对用户问题做轻量扩展后，再执行向量检索与关键词检索并去重融合。
 */
@Service
@Slf4j
public class ProRagRetrievalService {

    private static final int MAX_REWRITE_QUERIES = 3;
    private static final int RRF_K = 60;
    private static final int LOCATE_SNIPPET_LIMIT = 3;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ProRagElasticSearchService proRagElasticSearchService;

    @Autowired(required = false)
    private QuestionRewriteService questionRewriteService;

    @Autowired
    private ProRagRerankUtil proRagRerankUtil;

    public List<LocateResultItem> locateFiles(String query) throws Exception {
        QueryBundle bundle = buildQueries(query);
        List<SearchHit> vectorHits = searchVectorHits(bundle.vectorQueries(), null, 10, 0.35);
        List<SearchHit> keywordHits = searchKeywordHits(bundle.keywordQueries(), 6);

        Map<String, FileLocateAccumulator> groupedFiles = new LinkedHashMap<>();
        vectorHits.forEach(hit -> groupedFiles
                .computeIfAbsent(hit.filePath(), key -> new FileLocateAccumulator(hit.filePath(), hit.filename()))
                .addHit(hit));
        keywordHits.forEach(hit -> groupedFiles
                .computeIfAbsent(hit.filePath(), key -> new FileLocateAccumulator(hit.filePath(), hit.filename()))
                .addHit(hit));

        return groupedFiles.values().stream()
                .sorted(Comparator.comparingDouble(FileLocateAccumulator::score).reversed())
                .map(FileLocateAccumulator::toResult)
                .toList();
    }

    public GenerationReferenceBundle retrieveReferenceBundle(String query, String directiveFilename) throws Exception {
        QueryBundle bundle = buildQueries(query);
        List<Document> vectorDocs = searchVector(bundle.vectorQueries(), null, 10, 0.35);
        List<EsDocumentChunk> keywordDocs = searchKeyword(bundle.keywordQueries(), 5);

        if (StringUtils.hasText(directiveFilename)) {
            List<Document> directiveDocs = searchVector(
                    List.of(query),
                    directiveFilename.trim(),
                    8,
                    0.20
            );
            mergeVectorDocs(vectorDocs, directiveDocs);
        }

        List<String> contents = proRagRerankUtil.hybridFusion(vectorDocs, keywordDocs, bundle.originalQuery(), 8);
        List<ReferenceMaterial> referenceMaterials = buildReferenceMaterials(vectorDocs, keywordDocs, bundle.originalQuery());
        return new GenerationReferenceBundle(contents, referenceMaterials);
    }

    private List<ReferenceMaterial> buildReferenceMaterials(List<Document> vectorDocs, List<EsDocumentChunk> keywordDocs, String originalQuery) {
        Map<String, ReferenceMaterial> materials = new LinkedHashMap<>();

        vectorDocs.forEach(doc -> {
            String filePath = metadataValue(doc.getMetadata(), "filePath", "unknown");
            String filename = metadataValue(doc.getMetadata(), "filename", "unknown");
            materials.putIfAbsent(filePath, new ReferenceMaterial(
                    filePath,
                    filename,
                    extractSnippet(doc.getText(), originalQuery)
            ));
        });

        keywordDocs.forEach(doc -> {
            Map<String, Object> metadata = doc.getMetadata();
            String filePath = metadataValue(metadata, "filePath", "unknown");
            String filename = metadataValue(metadata, "filename", "unknown");
            materials.putIfAbsent(filePath, new ReferenceMaterial(
                    filePath,
                    filename,
                    extractSnippet(doc.getContent(), originalQuery)
            ));
        });

        return new ArrayList<>(materials.values()).stream().limit(6).toList();
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
                                .limit(MAX_REWRITE_QUERIES)
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
                        metadataValue(doc.getMetadata(), "filePath", "unknown"),
                        metadataValue(doc.getMetadata(), "filename", "unknown"),
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
                        metadataValue(metadata, "filePath", "unknown"),
                        metadataValue(metadata, "filename", "unknown"),
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
        double score() {
            return 1.0 / (RRF_K + rank);
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
            score += hit.score();
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
            List<LocateHitSnippet> snippets = hitSnippets.stream().limit(LOCATE_SNIPPET_LIMIT).toList();
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
