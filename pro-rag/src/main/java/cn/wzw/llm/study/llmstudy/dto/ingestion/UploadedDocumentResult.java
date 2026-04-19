package cn.wzw.llm.study.llmstudy.dto.ingestion;

public record UploadedDocumentResult(
        String originalFilename,
        String storedFilename,
        String filePath,
        int chunks,
        String status
) {
}
