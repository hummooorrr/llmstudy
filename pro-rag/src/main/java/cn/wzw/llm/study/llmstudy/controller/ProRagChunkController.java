package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.model.ChunkMetadataKeys;
import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import cn.wzw.llm.study.llmstudy.service.ProRagElasticSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 引用详情接口：前端点击 citation 角标时调用，返回 chunk 全文 + 邻居上下文 + asset URL。
 */
@RestController
@RequestMapping("/pro-rag/chunk")
public class ProRagChunkController {

    @Autowired
    private ProRagElasticSearchService proRagElasticSearchService;

    @GetMapping("/{chunkId}")
    public ResponseEntity<Map<String, Object>> getChunk(
            @PathVariable("chunkId") String chunkId,
            @RequestParam(value = "filename", required = false) String requestFilename,
            @RequestParam(value = "pageNumber", required = false) Integer requestPageNumber,
            @RequestParam(value = "excerpt", required = false) String excerpt,
            @RequestParam(value = "sectionPath", required = false) String sectionPath,
            @RequestParam(value = "chunkType", required = false) String chunkType
    ) throws Exception {
        Optional<EsDocumentChunk> chunkOpt = proRagElasticSearchService.findById(chunkId);
        if (chunkOpt.isEmpty()) {
            chunkOpt = resolveByHint(chunkId, requestFilename, requestPageNumber, excerpt, sectionPath, chunkType);
        }
        if (chunkOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        EsDocumentChunk chunk = chunkOpt.get();
        List<EsDocumentChunk> siblings = proRagElasticSearchService.findSiblings(chunk, 6);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chunk", toView(chunk));
        List<Map<String, Object>> siblingViews = new ArrayList<>();
        for (EsDocumentChunk sibling : siblings) {
            if (Objects.equals(sibling.getId(), chunk.getId())) {
                continue;
            }
            siblingViews.add(toView(sibling));
        }
        response.put("siblings", siblingViews);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> toView(EsDocumentChunk chunk) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", chunk.getId());
        view.put("content", chunk.getContent());
        Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
        view.put("filename", asString(metadata.get(ChunkMetadataKeys.FILENAME)));
        view.put("filePath", asString(metadata.get(ChunkMetadataKeys.FILE_PATH)));
        view.put("chunkType", asString(metadata.getOrDefault(ChunkMetadataKeys.CHUNK_TYPE, "TEXT")));
        view.put("pageNumber", asInteger(metadata.get(ChunkMetadataKeys.PAGE_NUMBER)));
        view.put("sectionPath", asString(metadata.get(ChunkMetadataKeys.SECTION_PATH)));
        view.put("chunkProfile", asString(metadata.get(ChunkMetadataKeys.CHUNK_PROFILE)));
        String assetPath = asString(metadata.get(ChunkMetadataKeys.ASSET_PATH));
        view.put("assetPath", assetPath);
        view.put("assetDescription", asString(metadata.get(ChunkMetadataKeys.ASSET_DESCRIPTION)));
        if (StringUtils.hasText(assetPath)) {
            view.put("assetUrl", "/pro-rag/asset/" + assetPath);
        }
        return view;
    }

    private Optional<EsDocumentChunk> resolveByHint(String chunkId, String filename, Integer pageNumber, String excerpt,
                                                    String sectionPath, String chunkType)
            throws Exception {
        if (!StringUtils.hasText(filename)) {
            return Optional.empty();
        }
        List<EsDocumentChunk> candidates = proRagElasticSearchService.findCandidates(filename, pageNumber, 200);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        Optional<EsDocumentChunk> metadataMatch = candidates.stream()
                .filter(candidate -> chunkId.equals(asString(metadataValue(candidate, ChunkMetadataKeys.CHUNK_ID))))
                .findFirst();
        if (metadataMatch.isPresent()) {
            return metadataMatch;
        }

        List<EsDocumentChunk> narrowed = narrowCandidates(candidates, sectionPath, chunkType);
        String normalizedExcerpt = normalizeExcerpt(excerpt);
        if (StringUtils.hasText(normalizedExcerpt)) {
            Optional<EsDocumentChunk> excerptMatch = selectBestByExcerpt(narrowed, normalizedExcerpt, sectionPath, chunkType)
                    .or(() -> selectBestByExcerpt(candidates, normalizedExcerpt, sectionPath, chunkType));
            if (excerptMatch.isPresent()) {
                return excerptMatch;
            }
        }

        if (!narrowed.isEmpty()) {
            return narrowed.stream().findFirst();
        }
        return candidates.stream().findFirst();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Integer asInteger(Object value) {
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

    private Object metadataValue(EsDocumentChunk chunk, String key) {
        Map<String, Object> metadata = chunk.getMetadata();
        return metadata == null ? null : metadata.get(key);
    }

    private String normalizeExcerpt(String excerpt) {
        String normalized = normalizeText(excerpt);
        while (normalized.startsWith("...")) {
            normalized = normalized.substring(3).trim();
        }
        while (normalized.endsWith("...")) {
            normalized = normalized.substring(0, normalized.length() - 3).trim();
        }
        return normalized;
    }

    private String normalizeText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private List<EsDocumentChunk> narrowCandidates(List<EsDocumentChunk> candidates, String sectionPath, String chunkType) {
        List<EsDocumentChunk> narrowed = candidates;
        if (StringUtils.hasText(chunkType)) {
            List<EsDocumentChunk> byType = narrowed.stream()
                    .filter(candidate -> normalizeText(chunkType)
                            .equals(normalizeText(asString(metadataValue(candidate, ChunkMetadataKeys.CHUNK_TYPE)))))
                    .toList();
            if (!byType.isEmpty()) {
                narrowed = byType;
            }
        }
        if (StringUtils.hasText(sectionPath)) {
            List<EsDocumentChunk> bySection = narrowed.stream()
                    .filter(candidate -> normalizeText(sectionPath)
                            .equals(normalizeText(asString(metadataValue(candidate, ChunkMetadataKeys.SECTION_PATH)))))
                    .toList();
            if (!bySection.isEmpty()) {
                narrowed = bySection;
            }
        }
        return narrowed;
    }

    private Optional<EsDocumentChunk> selectBestByExcerpt(List<EsDocumentChunk> candidates, String normalizedExcerpt,
                                                          String sectionPath, String chunkType) {
        if (candidates == null || candidates.isEmpty() || !StringUtils.hasText(normalizedExcerpt)) {
            return Optional.empty();
        }
        return candidates.stream()
                .map(candidate -> Map.entry(candidate, scoreCandidate(candidate, normalizedExcerpt, sectionPath, chunkType)))
                .filter(entry -> entry.getValue() > 0)
                .max(Comparator.<Map.Entry<EsDocumentChunk, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(entry -> entry.getKey().getId(), Comparator.nullsLast(String::compareTo)))
                .map(Map.Entry::getKey);
    }

    private int scoreCandidate(EsDocumentChunk candidate, String normalizedExcerpt, String sectionPath, String chunkType) {
        int score = 0;
        String normalizedContent = normalizeText(candidate.getContent());
        int excerptIndex = normalizedContent.indexOf(normalizedExcerpt);
        if (excerptIndex >= 0) {
            score += 1000;
            score += Math.min(200, normalizedExcerpt.length());
            score += Math.max(0, 100 - excerptIndex);
        }
        if (StringUtils.hasText(chunkType) && normalizeText(chunkType)
                .equals(normalizeText(asString(metadataValue(candidate, ChunkMetadataKeys.CHUNK_TYPE))))) {
            score += 120;
        }
        if (StringUtils.hasText(sectionPath) && normalizeText(sectionPath)
                .equals(normalizeText(asString(metadataValue(candidate, ChunkMetadataKeys.SECTION_PATH))))) {
            score += 240;
        }
        return score;
    }
}
