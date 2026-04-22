package cn.wzw.llm.study.llmstudy.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 抽取出的图片 / 表格 snapshot 落盘管理。统一目录为 ${upload-dir}/assets/{bucket}/{assetId}.{ext}
 * 对外暴露相对路径作为 chunk metadata 的 assetPath，前端通过 /pro-rag/asset/** 回源。
 */
@Service
@Slf4j
public class AssetStorageService {

    @Value("${pro-rag.upload-dir:./pro-rag-files}")
    private String uploadDir;

    public Path assetsRoot() {
        return Paths.get(uploadDir).toAbsolutePath().normalize().resolve("assets");
    }

    /**
     * 把图片字节落盘，返回"相对 assetsRoot 的路径"。
     *
     * @param sourceFilePath 原始文件的绝对路径，用于生成 bucket 名
     * @param extension      含点扩展名，例如 ".png"
     */
    public String saveImage(String sourceFilePath, String extension, byte[] bytes) throws IOException {
        String bucket = bucketOf(sourceFilePath);
        Path bucketDir = assetsRoot().resolve(bucket);
        Files.createDirectories(bucketDir);

        String assetId = UUID.randomUUID().toString();
        String filename = assetId + safeExtension(extension);
        Path target = bucketDir.resolve(filename);
        Files.write(target, bytes);
        return bucket + "/" + filename;
    }

    /**
     * 按 sourceFilePath 得到 bucket 名（sha1 前 16 位），一份源文件对应一个目录，方便整份清理。
     */
    public String bucketOf(String sourceFilePath) {
        if (sourceFilePath == null || sourceFilePath.isBlank()) {
            return "misc";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(sourceFilePath.getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(8, digest.length); i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "misc";
        }
    }

    public Path resolveAsset(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path root = assetsRoot();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法 asset 路径: " + relativePath);
        }
        return target;
    }

    /**
     * 清理某份源文件相关的全部 asset。用于 purgeUploadedFile。
     */
    public int purgeBucket(String sourceFilePath) {
        Path bucketDir = assetsRoot().resolve(bucketOf(sourceFilePath));
        if (!Files.exists(bucketDir)) {
            return 0;
        }
        int deleted = 0;
        try (var stream = Files.list(bucketDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                try {
                    Files.deleteIfExists(file);
                    deleted++;
                } catch (IOException ignored) {
                }
            }
            Files.deleteIfExists(bucketDir);
        } catch (IOException e) {
            log.warn("清理 asset 目录失败: {} -> {}", bucketDir, e.getMessage());
        }
        return deleted;
    }

    private String safeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return ".bin";
        }
        String lower = extension.toLowerCase();
        if (!lower.startsWith(".")) {
            lower = "." + lower;
        }
        if (!lower.matches("\\.[a-z0-9]{1,8}")) {
            return ".bin";
        }
        return lower;
    }
}
