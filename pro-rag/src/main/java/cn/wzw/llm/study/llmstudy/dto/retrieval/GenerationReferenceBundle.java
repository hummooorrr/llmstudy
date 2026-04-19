package cn.wzw.llm.study.llmstudy.dto.retrieval;

import java.util.List;

public record GenerationReferenceBundle(
        List<String> contents,
        List<ReferenceMaterial> referenceMaterials
) {
}
