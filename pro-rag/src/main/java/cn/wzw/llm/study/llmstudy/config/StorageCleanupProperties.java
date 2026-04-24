package cn.wzw.llm.study.llmstudy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储清理配置，统一绑定 pro-rag.cleanup 前缀。
 */
@ConfigurationProperties(prefix = "pro-rag.cleanup")
public record StorageCleanupProperties(
        boolean enabled,
        int uploadRetentionDays,
        int generatedRetentionDays,
        String cron,
        boolean dryRun
) {
    public StorageCleanupProperties {
        if (uploadRetentionDays <= 0) uploadRetentionDays = 7;
        if (generatedRetentionDays <= 0) generatedRetentionDays = 7;
        if (cron == null || cron.isBlank()) cron = "0 30 3 * * ?";
    }
}
