package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.service.AssetStorageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 对外暴露抽取出的图片 / 表格 snapshot，只允许读取 {@code ${upload-dir}/assets/} 下文件。
 */
@RestController
@RequestMapping("/pro-rag/asset")
public class ProRagAssetController {

    private final AssetStorageService assetStorageService;

    public ProRagAssetController(AssetStorageService assetStorageService) {
        this.assetStorageService = assetStorageService;
    }

    @GetMapping("/**")
    public ResponseEntity<?> serveAsset(HttpServletRequest request) {
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        if (path == null) {
            path = request.getRequestURI().replaceFirst("^/pro-rag/asset/?", "");
        }
        if (path.isBlank()) {
            return ResponseEntity.badRequest().body("asset path required");
        }

        Path target;
        try {
            target = assetStorageService.resolveAsset(path);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        if (target == null || !Files.exists(target) || !Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }

        String contentType = URLConnection.guessContentTypeFromName(target.getFileName().toString());
        if (contentType == null) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                .body(new FileSystemResource(target));
    }
}
