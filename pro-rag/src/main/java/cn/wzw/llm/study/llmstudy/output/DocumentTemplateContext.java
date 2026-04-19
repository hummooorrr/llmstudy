package cn.wzw.llm.study.llmstudy.output;

import cn.wzw.llm.study.llmstudy.dto.retrieval.ReferenceMaterial;

import java.util.List;

public record DocumentTemplateContext(
        String documentTitle,
        String generatedAt,
        String directiveFilename,
        String instruction,
        String referenceFiles,
        String body,
        List<ReferenceMaterial> referenceMaterials
) {
}
