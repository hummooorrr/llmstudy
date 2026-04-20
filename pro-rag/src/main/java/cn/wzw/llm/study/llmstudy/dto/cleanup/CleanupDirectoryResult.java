package cn.wzw.llm.study.llmstudy.dto.cleanup;

import java.util.List;

public record CleanupDirectoryResult(
        String target,
        String directoryPath,
        int retentionDays,
        int scannedFiles,
        int expiredFiles,
        int deletedFiles,
        long releasedBytes,
        List<String> deletedFilenames,
        List<String> failedFiles,
        List<String> skippedFiles
) {
}
