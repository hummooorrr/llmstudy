package cn.wzw.llm.study.llmstudy.controller;

import cn.wzw.llm.study.llmstudy.dto.ingestion.UploadedDocumentResult;
import cn.wzw.llm.study.llmstudy.service.ProRagDocumentIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传入库接口
 * 接收 MultipartFile → 保存本地 → 分片 → 写入 PgVector + ES
 */
@Slf4j
@RestController
@RequestMapping("/pro-rag")
public class ProRagUploadController {

    @Autowired
    private ProRagDocumentIngestionService proRagDocumentIngestionService;

    /**
     * 上传文件并入库
     * 支持 PDF、Word、Markdown、HTML、TXT、JSON 等格式
     *
     * @param file    待上传文件
     * @param profile 可选的分块 profile（如 pdf-scanned / markdown / word），留空则按扩展名自动选择
     */
    @PostMapping("/upload")
    public UploadedDocumentResult upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "profile", required = false) String profile
    ) throws Exception {
        log.info("[上传] 收到上传请求: 文件名={}, 大小={}KB, profile={}",
                file.getOriginalFilename(), file.getSize() / 1024, profile);
        long start = System.currentTimeMillis();
        try {
            UploadedDocumentResult result = proRagDocumentIngestionService.upload(file, profile);
            log.info("[上传] 完成: 文件={}, chunk数={}, 耗时={}ms",
                    result.originalFilename(), result.chunks(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("[上传] 失败: 文件={}, 耗时={}ms, 错误={}",
                    file.getOriginalFilename(), System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 重新入库：对已落盘的文件重新执行分片、嵌入和 ES 写入
     * 用于入库失败后的补录
     *
     * @param filename 上传目录中的文件名
     * @param profile  可选的分块 profile 覆盖
     */
    @PostMapping("/reingest")
    public UploadedDocumentResult reingest(
            @RequestParam("filename") String filename,
            @RequestParam(value = "profile", required = false) String profile
    ) throws Exception {
        log.info("[补录] 收到补录请求: 文件名={}, profile={}", filename, profile);
        long start = System.currentTimeMillis();
        try {
            UploadedDocumentResult result = proRagDocumentIngestionService.reingest(filename, profile);
            log.info("[补录] 完成: 文件={}, chunk数={}, 耗时={}ms",
                    result.originalFilename(), result.chunks(), System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("[补录] 失败: 文件={}, 耗时={}ms, 错误={}",
                    filename, System.currentTimeMillis() - start, e.getMessage(), e);
            throw e;
        }
    }
}
