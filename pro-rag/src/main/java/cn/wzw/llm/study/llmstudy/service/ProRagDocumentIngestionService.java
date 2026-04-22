package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.dto.ingestion.UploadedDocumentResult;
import cn.wzw.llm.study.llmstudy.model.ChunkMetadataKeys;
import cn.wzw.llm.study.llmstudy.model.ChunkType;
import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import cn.wzw.llm.study.llmstudy.service.pdf.PdfIngestionPipeline;
import cn.wzw.llm.study.llmstudy.splitter.SplitterFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件接收、分片和入库服务。
 * 把上传落盘、切分和双写存储集中到一个地方，便于复用到"上传即生成"等场景。
 */
@Service
@Slf4j
public class ProRagDocumentIngestionService {

    @Value("${pro-rag.upload-dir:./pro-rag-files}")
    private String uploadDir;

    @Autowired
    private DocumentReaderStrategySelector documentReaderStrategySelector;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ProRagElasticSearchService proRagElasticSearchService;

    @Autowired
    private SplitterFactory splitterFactory;

    @Autowired
    private PdfIngestionPipeline pdfIngestionPipeline;

    @Autowired
    private DocxIngestionPipeline docxIngestionPipeline;

    public UploadedDocumentResult upload(MultipartFile file) throws Exception {
        return upload(file, null);
    }

    /**
     * 上传并入库。profile 用于覆盖默认的按扩展名自动选 profile 逻辑，
     * 例如同为 pdf 但业务场景不同时可传入 "pdf-scanned" 强制走扫描件流水线。
     */
    public UploadedDocumentResult upload(MultipartFile file, String profileOverride) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }

        Path uploadPath = Paths.get(uploadDir).toAbsolutePath();
        Files.createDirectories(uploadPath);

        String originalFilename = StringUtils.cleanPath(resolveOriginalFilename(file));
        Path storedPath = resolveUniquePath(uploadPath, originalFilename);
        file.transferTo(storedPath.toFile());

        List<Document> chunks = splitDocuments(storedPath.toFile(), profileOverride);
        if (CollectionUtils.isEmpty(chunks)) {
            throw new IllegalArgumentException("文件解析后没有可入库内容: " + originalFilename);
        }

        List<EsDocumentChunk> esDocs = toEsDocs(chunks);

        boolean vectorStored = false;
        boolean esStored = false;
        try {
            embeddingService.embedAndStore(chunks);
            vectorStored = true;
            proRagElasticSearchService.bulkIndex(esDocs);
            esStored = true;
        } catch (Exception e) {
            log.error("文档入库失败: {}", e.getMessage(), e);
            if (vectorStored && !esStored) {
                log.error("向量库已写入但 ES 索引失败，需手动补录 {} 条 chunk，文件: {}",
                        esDocs.size(), originalFilename);
            }
            throw e;
        }

        return new UploadedDocumentResult(
                originalFilename,
                storedPath.getFileName().toString(),
                storedPath.toAbsolutePath().toString(),
                chunks.size(),
                "success"
        );
    }

    private List<EsDocumentChunk> toEsDocs(List<Document> chunks) {
        return chunks.stream().map(doc -> {
            EsDocumentChunk es = new EsDocumentChunk();
            es.setId(doc.getId());
            es.setContent(doc.getText());
            es.setMetadata(doc.getMetadata());
            return es;
        }).toList();
    }

    private String resolveOriginalFilename(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            throw new IllegalArgumentException("无法识别上传文件名");
        }
        return Paths.get(originalFilename).getFileName().toString();
    }

    private Path resolveUniquePath(Path uploadPath, String originalFilename) throws Exception {
        String sanitizedFilename = sanitizeFilename(originalFilename);
        Path targetPath = uploadPath.resolve(sanitizedFilename);
        if (!Files.exists(targetPath)) {
            return targetPath;
        }

        int dotIndex = sanitizedFilename.lastIndexOf('.');
        String baseName = dotIndex >= 0 ? sanitizedFilename.substring(0, dotIndex) : sanitizedFilename;
        String extension = dotIndex >= 0 ? sanitizedFilename.substring(dotIndex) : "";

        int counter = 1;
        while (Files.exists(targetPath)) {
            targetPath = uploadPath.resolve(baseName + "_" + counter + extension);
            counter++;
        }
        return targetPath;
    }

    private String sanitizeFilename(String filename) {
        String sanitized = filename.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (!StringUtils.hasText(sanitized)) {
            throw new IllegalArgumentException("文件名不合法");
        }
        return sanitized;
    }

    private List<Document> splitDocuments(File localFile, String profileOverride) throws Exception {
        String fileName = localFile.getName().toLowerCase();

        if (fileName.endsWith(".pdf")) {
            String profile = StringUtils.hasText(profileOverride) ? profileOverride : SplitterFactory.PROFILE_PDF_TEXT;
            List<Document> chunks = pdfIngestionPipeline.process(localFile, profile);
            if (CollectionUtils.isNotEmpty(chunks)) {
                return chunks;
            }
        }

        if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            String rawText = Files.readString(localFile.toPath());
            Map<String, Object> metadata = sourceMetadata(localFile);
            List<Document> chunks = splitterFactory.split(
                    List.of(new Document(rawText, metadata)),
                    StringUtils.hasText(profileOverride) ? profileOverride : SplitterFactory.PROFILE_MARKDOWN
            );
            return stampDefaultChunkType(chunks, ChunkType.TEXT);
        }

        if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            String profile = StringUtils.hasText(profileOverride) ? profileOverride : SplitterFactory.PROFILE_WORD;
            return docxIngestionPipeline.process(localFile, profile);
        }

        List<Document> documents = clean(documentReaderStrategySelector.read(localFile));
        String profile = StringUtils.hasText(profileOverride) ? profileOverride : SplitterFactory.PROFILE_DEFAULT;
        List<Document> chunks = splitterFactory.split(documents, profile);
        ChunkType defaultType = fileName.matches(".*\\.(jpg|jpeg|png|bmp|tiff|gif)$") ? ChunkType.IMAGE : ChunkType.TEXT;
        return stampDefaultChunkType(chunks, defaultType);
    }

    private List<Document> stampDefaultChunkType(List<Document> chunks, ChunkType defaultType) {
        if (CollectionUtils.isEmpty(chunks)) {
            return chunks;
        }
        return chunks.stream().map(doc -> {
            Map<String, Object> metadata = doc.getMetadata() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(doc.getMetadata());
            metadata.putIfAbsent(ChunkMetadataKeys.CHUNK_TYPE, defaultType.name());
            return new Document(doc.getText(), metadata);
        }).toList();
    }

    private Map<String, Object> sourceMetadata(File file) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ChunkMetadataKeys.FILENAME, file.getName());
        metadata.put(ChunkMetadataKeys.FILE_PATH, file.getAbsolutePath());
        return metadata;
    }

    /**
     * 重新入库：对已落盘的文件重新执行分片、嵌入和 ES 写入。
     * 用于入库失败后的补录，或更新已有文件的内容。
     *
     * @param filename 上传目录中的文件名
     */
    public UploadedDocumentResult reingest(String filename) throws Exception {
        return reingest(filename, null);
    }

    public UploadedDocumentResult reingest(String filename, String profileOverride) throws Exception {
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        String safeName = Paths.get(filename).getFileName().toString();
        Path filePath = uploadPath.resolve(safeName).normalize();
        if (!filePath.startsWith(uploadPath)) {
            throw new IllegalArgumentException("非法文件路径: " + filename);
        }
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("文件不存在: " + safeName + "，可能已被 7 天保留策略自动清理，无法补录");
        }

        List<String> oldVectorIds = Collections.emptyList();
        List<String> oldEsIds = Collections.emptyList();
        try {
            oldVectorIds = embeddingService.findIdsByFilename(safeName);
        } catch (Exception e) {
            log.warn("查询向量库旧数据失败: {}", e.getMessage());
        }
        try {
            oldEsIds = proRagElasticSearchService.findIdsByFilename(safeName);
        } catch (Exception e) {
            log.warn("查询 ES 旧数据失败: {}", e.getMessage());
        }

        List<Document> chunks = splitDocuments(filePath.toFile(), profileOverride);
        if (CollectionUtils.isEmpty(chunks)) {
            throw new IllegalArgumentException("文件解析后没有可入库内容: " + safeName);
        }

        List<EsDocumentChunk> esDocs = toEsDocs(chunks);

        embeddingService.embedAndStore(chunks);
        proRagElasticSearchService.bulkIndex(esDocs);

        try {
            embeddingService.deleteByIds(oldVectorIds);
        } catch (Exception e) {
            log.warn("清理向量库旧数据失败，可手动重试: {}", e.getMessage());
        }
        try {
            proRagElasticSearchService.deleteByIds(oldEsIds);
        } catch (Exception e) {
            log.warn("清理 ES 旧数据失败，可手动重试: {}", e.getMessage());
        }

        return new UploadedDocumentResult(
                safeName,
                filePath.getFileName().toString(),
                filePath.toAbsolutePath().toString(),
                chunks.size(),
                "reingested"
        );
    }

    /**
     * 轻量清洗：去除多余空行和首尾空白。
     */
    private List<Document> clean(List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return documents;
        }
        return documents.stream()
                .map(doc -> {
                    if (doc == null || doc.getText() == null) {
                        return doc;
                    }
                    String text = doc.getText().replaceAll("\n{3,}", "\n\n").trim();
                    return new Document(text, doc.getMetadata());
                })
                .toList();
    }
}
