package cn.wzw.llm.study.llmstudy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pro-rag.cleanup")
public record StorageCleanupProperties(
        boolean enabled,
        int uploadRetentionDays,
        int generatedRetentionDays,
        String cron,
        boolean dryRun
) {
}
