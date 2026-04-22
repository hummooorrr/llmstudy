package cn.wzw.llm.study.llmstudy.dto.retrieval;

/**
 * 一次检索命中的"参考材料"单元。refId 在一次请求内稳定（例如 c1..cN），
 * LLM 通过 [^c1] 格式角标引用，前端据此渲染可点击 citation。
 */
public record ReferenceMaterial(
        String refId,
        String chunkId,
        String filePath,
        String filename,
        String excerpt,
        double score,
        String chunkType,
        Integer pageNumber,
        String sectionPath,
        String assetPath
) {
}
