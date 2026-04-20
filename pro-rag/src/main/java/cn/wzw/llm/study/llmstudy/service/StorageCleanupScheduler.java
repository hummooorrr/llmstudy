package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.config.StorageCleanupProperties;
import cn.wzw.llm.study.llmstudy.dto.cleanup.CleanupTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StorageCleanupScheduler {

    private final StorageCleanupProperties storageCleanupProperties;
    private final StorageCleanupService storageCleanupService;

    public StorageCleanupScheduler(
            StorageCleanupProperties storageCleanupProperties,
            StorageCleanupService storageCleanupService
    ) {
        this.storageCleanupProperties = storageCleanupProperties;
        this.storageCleanupService = storageCleanupService;
    }

    @Scheduled(cron = "${pro-rag.cleanup.cron}")
    public void cleanupExpiredLocalFiles() {
        if (!storageCleanupProperties.enabled()) {
            log.info("存储清理已禁用，跳过本次定时任务");
            return;
        }
        log.info("开始执行定时存储清理，dryRun={}", storageCleanupProperties.dryRun());
        storageCleanupService.cleanupExpiredLocalFiles(CleanupTarget.ALL, storageCleanupProperties.dryRun());
    }
}
