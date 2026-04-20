package cn.wzw.llm.study.llmstudy.dto.cleanup;

import java.util.List;

public record CleanupExecutionResult(
        String mode,
        String target,
        boolean dryRun,
        String executedAt,
        int totalDeletedFiles,
        long totalReleasedBytes,
        List<CleanupDirectoryResult> directories
) {
}
