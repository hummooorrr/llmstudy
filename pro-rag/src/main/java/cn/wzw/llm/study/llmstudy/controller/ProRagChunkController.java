package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.model.ChunkMetadataKeys;
import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import cn.wzw.llm.study.llmstudy.service.ProRagElasticSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public ResponseEntity<Map<String, Object>> getChunk(@PathVariable("chunkId") String chunkId) throws Exception {
        Optional<EsDocumentChunk> chunkOpt = proRagElasticSearchService.findById(chunkId);
        if (chunkOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        EsDocumentChunk chunk = chunkOpt.get();
        Map<String, Object> metadata = chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
        String filename = asString(metadata.get(ChunkMetadataKeys.FILENAME));
        Integer pageNumber = asInteger(metadata.get(ChunkMetadataKeys.PAGE_NUMBER));

        List<EsDocumentChunk> siblings = proRagElasticSearchService.findSiblings(filename, pageNumber, 6);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("chunk", toView(chunk));
        List<Map<String, Object>> siblingViews = new ArrayList<>();
        for (EsDocumentChunk sibling : siblings) {
            if (sibling.getId().equals(chunk.getId())) {
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
}
