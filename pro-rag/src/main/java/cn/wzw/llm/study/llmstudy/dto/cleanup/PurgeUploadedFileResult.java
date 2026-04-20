package cn.wzw.llm.study.llmstudy.dto.cleanup;

public record PurgeUploadedFileResult(
        String filename,
        boolean dryRun,
        boolean uploadFileExisted,
        boolean uploadFileDeleted,
        int vectorDeletedCount,
        int esDeletedCount,
        String message
) {
}
