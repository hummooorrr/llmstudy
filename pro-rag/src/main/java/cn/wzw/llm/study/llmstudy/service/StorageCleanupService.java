package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.config.StorageCleanupProperties;
import cn.wzw.llm.study.llmstudy.dto.cleanup.CleanupDirectoryResult;
import cn.wzw.llm.study.llmstudy.dto.cleanup.CleanupExecutionResult;
import cn.wzw.llm.study.llmstudy.dto.cleanup.CleanupTarget;
import cn.wzw.llm.study.llmstudy.dto.cleanup.PurgeUploadedFileResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Service
@Slf4j
public class StorageCleanupService {

    private static final DateTimeFormatter DISPLAY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${pro-rag.upload-dir:./pro-rag-files}")
    private String uploadDir;

    @Value("${pro-rag.generated-dir:./pro-rag-generated}")
    private String generatedDir;

    private final StorageCleanupProperties storageCleanupProperties;
    private final EmbeddingService embeddingService;
    private final ProRagElasticSearchService proRagElasticSearchService;
    private final AssetStorageService assetStorageService;

    public StorageCleanupService(
            StorageCleanupProperties storageCleanupProperties,
            EmbeddingService embeddingService,
            ProRagElasticSearchService proRagElasticSearchService,
            AssetStorageService assetStorageService
    ) {
        this.storageCleanupProperties = storageCleanupProperties;
        this.embeddingService = embeddingService;
        this.proRagElasticSearchService = proRagElasticSearchService;
        this.assetStorageService = assetStorageService;
    }

    public boolean isCleanupEnabled() {
        return storageCleanupProperties.enabled();
    }

    public CleanupExecutionResult cleanupExpiredLocalFiles(CleanupTarget target, Boolean dryRunOverride) {
        CleanupTarget resolvedTarget = target == null ? CleanupTarget.ALL : target;
        boolean dryRun = dryRunOverride != null ? dryRunOverride : storageCleanupProperties.dryRun();
        List<CleanupDirectoryResult> directories = new ArrayList<>();

        if (resolvedTarget.includesUploads()) {
            directories.add(cleanDirectory("uploads", uploadDir, storageCleanupProperties.uploadRetentionDays(), dryRun));
        }
        if (resolvedTarget.includesGenerated()) {
            directories.add(cleanDirectory("generated", generatedDir, storageCleanupProperties.generatedRetentionDays(), dryRun));
        }

        int totalDeletedFiles = directories.stream().mapToInt(CleanupDirectoryResult::deletedFiles).sum();
        long totalReleasedBytes = directories.stream().mapToLong(CleanupDirectoryResult::releasedBytes).sum();
        String executedAt = LocalDateTime.now().format(DISPLAY_TIME_FORMATTER);

        log.info("存储清理完成 mode=local-only, target={}, dryRun={}, deletedFiles={}, releasedBytes={}",
                resolvedTarget, dryRun, totalDeletedFiles, totalReleasedBytes);

        return new CleanupExecutionResult(
                "local-only",
                resolvedTarget.name().toLowerCase(),
                dryRun,
                executedAt,
                totalDeletedFiles,
                totalReleasedBytes,
                directories
        );
    }

    public PurgeUploadedFileResult purgeUploadedFile(String filename, Boolean dryRunOverride) throws Exception {
        String safeFilename = resolveSafeFilename(filename);
        boolean dryRun = dryRunOverride != null ? dryRunOverride : storageCleanupProperties.dryRun();
        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path uploadFile = uploadRoot.resolve(safeFilename).normalize();
        if (!uploadFile.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("非法文件路径: " + filename);
        }

        boolean fileExists = Files.exists(uploadFile);
        List<String> vectorIds = embeddingService.findIdsByFilename(safeFilename);
        List<String> esIds = proRagElasticSearchService.findIdsByFilename(safeFilename);

        boolean fileDeleted = false;
        int purgedAssetCount = 0;
        if (!dryRun) {
            embeddingService.deleteByIds(vectorIds);
            proRagElasticSearchService.deleteByIds(esIds);
            if (fileExists) {
                Files.delete(uploadFile);
                fileDeleted = true;
            }
            try {
                purgedAssetCount = assetStorageService.purgeBucket(uploadFile.toAbsolutePath().toString());
            } catch (Exception e) {
                log.warn("清理 asset 目录失败: {} -> {}", uploadFile, e.getMessage());
            }
        }

        String message = dryRun
                ? "演练完成，将删除本地上传文件并同步清理向量库与 ES 索引。"
                : "已完成上传文件及其知识库索引清理。";

        log.info("上传文件彻底清理 filename={}, dryRun={}, fileExists={}, vectorIds={}, esIds={}, assets={}",
                safeFilename, dryRun, fileExists, vectorIds.size(), esIds.size(), purgedAssetCount);

        return new PurgeUploadedFileResult(
                safeFilename,
                dryRun,
                fileExists,
                fileDeleted,
                vectorIds.size(),
                esIds.size(),
                message
        );
    }

    private CleanupDirectoryResult cleanDirectory(String target, String directory, int retentionDays, boolean dryRun) {
        Path root = Paths.get(directory).toAbsolutePath().normalize();
        List<String> deletedFilenames = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();
        int scannedFiles = 0;
        int expiredFiles = 0;
        int deletedFiles = 0;
        long releasedBytes = 0L;

        if (!Files.exists(root)) {
            skippedFiles.add("目录不存在: " + root);
            return new CleanupDirectoryResult(target, root.toString(), retentionDays, 0, 0, 0, 0L,
                    deletedFilenames, failedFiles, skippedFiles);
        }

        Instant deadline = Instant.now().minusSeconds(retentionDays * 24L * 60L * 60L);
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            scannedFiles = files.size();
            for (Path file : files) {
                FileTime lastModifiedTime = Files.getLastModifiedTime(file);
                if (lastModifiedTime.toInstant().isAfter(deadline)) {
                    continue;
                }
                expiredFiles++;
                long fileSize = fileSize(file);
                if (dryRun) {
                    deletedFiles++;
                    releasedBytes += fileSize;
                    deletedFilenames.add(file.getFileName().toString());
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                    deletedFiles++;
                    releasedBytes += fileSize;
                    deletedFilenames.add(file.getFileName().toString());
                } catch (Exception e) {
                    failedFiles.add(file.getFileName() + " -> " + e.getMessage());
                }
            }
        } catch (IOException e) {
            failedFiles.add("扫描目录失败: " + e.getMessage());
        }

        return new CleanupDirectoryResult(
                target,
                root.toString(),
                retentionDays,
                scannedFiles,
                expiredFiles,
                deletedFiles,
                releasedBytes,
                deletedFilenames,
                failedFiles,
                skippedFiles
        );
    }

    private long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private String resolveSafeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("filename 不能为空");
        }
        return Paths.get(filename.trim()).getFileName().toString();
    }
}
