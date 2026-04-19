package cn.wzw.llm.study.llmstudy.dto.generate;

import cn.wzw.llm.study.llmstudy.dto.ingestion.UploadedDocumentResult;
import cn.wzw.llm.study.llmstudy.dto.retrieval.ReferenceMaterial;

import java.util.List;

public record GeneratedFileResult(
        String status,
        String templateName,
        String outputFormat,
        String directiveFilename,
        String outputFilename,
        String outputFilePath,
        String previewContent,
        List<ReferenceMaterial> referenceMaterials,
        UploadedDocumentResult directiveFile
) {
}
