package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.dto.cleanup.CleanupExecutionResult;
import cn.wzw.llm.study.llmstudy.dto.cleanup.CleanupTarget;
import cn.wzw.llm.study.llmstudy.dto.cleanup.PurgeUploadedFileResult;
import cn.wzw.llm.study.llmstudy.service.StorageCleanupService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pro-rag/cleanup")
public class StorageCleanupController {

    private final StorageCleanupService storageCleanupService;

    public StorageCleanupController(StorageCleanupService storageCleanupService) {
        this.storageCleanupService = storageCleanupService;
    }

    @PostMapping("/local-files")
    public CleanupExecutionResult cleanupLocalFiles(
            @RequestParam(value = "target", defaultValue = "all") String target,
            @RequestParam(value = "dryRun", required = false) Boolean dryRun
    ) {
        return storageCleanupService.cleanupExpiredLocalFiles(CleanupTarget.from(target), dryRun);
    }

    @PostMapping("/purge-uploaded-file")
    public PurgeUploadedFileResult purgeUploadedFile(
            @RequestParam("filename") String filename,
            @RequestParam(value = "dryRun", required = false) Boolean dryRun
    ) throws Exception {
        return storageCleanupService.purgeUploadedFile(filename, dryRun);
    }
}
