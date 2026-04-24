package cn.wzw.llm.study.llmstudy.service;

import cn.wzw.llm.study.llmstudy.dto.ingestion.UploadedDocumentResult;
import cn.wzw.llm.study.llmstudy.model.ChunkMetadataKeys;
import cn.wzw.llm.study.llmstudy.model.ChunkType;
import cn.wzw.llm.study.llmstudy.model.EsDocumentChunk;
import cn.wzw.llm.study.llmstudy.splitter.SplitterFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件接收、分片和入库服务。
 * 把上传落盘、切分和双写存储集中到一个地方，便于复用到"上传即生成"等场景。
 * <p>
 * 通过 {@link DocumentIngestionPipeline} 列表实现可扩展的文件格式处理。
 */
@Service
@Slf4j
public class ProRagDocumentIngestionService {

    @Value("${pro-rag.upload-dir:./pro-rag-files}")
    private String uploadDir;

    private final DocumentReaderStrategySelector documentReaderStrategySelector;
    private final EmbeddingService embeddingService;
    private final ProRagElasticSearchService proRagElasticSearchService;
    private final SplitterFactory splitterFactory;
    private final List<DocumentIngestionPipeline> ingestionPipelines;

    public ProRagDocumentIngestionService(
            DocumentReaderStrategySelector documentReaderStrategySelector,
            EmbeddingService embeddingService,
            ProRagElasticSearchService proRagElasticSearchService,
            SplitterFactory splitterFactory,
            List<DocumentIngestionPipeline> ingestionPipelines) {
        this.documentReaderStrategySelector = documentReaderStrategySelector;
        this.embeddingService = embeddingService;
        this.proRagElasticSearchService = proRagElasticSearchService;
        this.splitterFactory = splitterFactory;
        this.ingestionPipelines = ingestionPipelines;
    }

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

        List<Document> chunks = normalizeChunkIds(splitDocuments(storedPath.toFile(), profileOverride));
        if (CollectionUtils.isEmpty(chunks)) {
            throw new IllegalArgumentException("文件解析后没有可入库内容: " + originalFilename);
        }

        writeToStores(chunks, originalFilename, storedPath);

        return new UploadedDocumentResult(
                originalFilename,
                storedPath.getFileName().toString(),
                storedPath.toAbsolutePath().toString(),
                chunks.size(),
                "success"
        );
    }

    /**
     * 双写向量库和 ES，任意失败都不留下孤儿数据。
     */
    private void writeToStores(List<Document> chunks, String originalFilename, Path storedPath) throws Exception {
        List<EsDocumentChunk> esDocs = toEsDocs(chunks);

        // Step 1: 写向量库
        embeddingService.embedAndStore(chunks);

        // Step 2: 写 ES，失败时回滚向量库
        try {
            proRagElasticSearchService.bulkIndex(esDocs);
        } catch (Exception e) {
            log.error("ES 索引失败，回滚向量库数据: 文件={}, chunk数={}", originalFilename, esDocs.size());
            List<String> vectorIds = chunks.stream().map(Document::getId).toList();
            try {
                embeddingService.deleteByIds(vectorIds);
                log.info("已回滚向量库 {} 条数据", vectorIds.size());
            } catch (Exception rollbackEx) {
                log.error("回滚向量库失败，需手动清理: {}", rollbackEx.getMessage());
            }
            throw new RuntimeException("文档入库失败（ES 写入异常，向量数据已回滚）", e);
        }
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

    /**
     * 按注册的 {@link DocumentIngestionPipeline} 列表匹配处理（按 @Order 顺序），
     * 专用流水线返回空时兜底回退到通用读取器路径，避免极端文件直接丢失。
     */
    private List<Document> splitDocuments(File localFile, String profileOverride) throws Exception {
        for (DocumentIngestionPipeline pipeline : ingestionPipelines) {
            if (!pipeline.supports(localFile)) {
                continue;
            }
            List<Document> chunks = pipeline.process(localFile, profileOverride);
            if (CollectionUtils.isNotEmpty(chunks)) {
                return chunks;
            }
            log.warn("专用流水线 {} 解析 {} 返回空，回退到通用读取器",
                    pipeline.getClass().getSimpleName(), localFile.getName());
            break;
        }
        return splitWithGenericReader(localFile, profileOverride);
    }

    private List<Document> splitWithGenericReader(File localFile, String profileOverride) throws Exception {
        String fileName = localFile.getName().toLowerCase();

        // Markdown 走专门的 MarkdownHeaderTextSplitter，保留标题层级，便于父子 chunk
        if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            String rawText = Files.readString(localFile.toPath());
            Map<String, Object> metadata = sourceMetadata(localFile);
            String profile = StringUtils.hasText(profileOverride) ? profileOverride : SplitterFactory.PROFILE_MARKDOWN;
            List<Document> chunks = splitterFactory.split(
                    List.of(new Document(rawText, metadata)), profile);
            return stampDefaultChunkType(chunks, ChunkType.TEXT);
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

        List<Document> chunks = normalizeChunkIds(splitDocuments(filePath.toFile(), profileOverride));
        if (CollectionUtils.isEmpty(chunks)) {
            throw new IllegalArgumentException("文件解析后没有可入库内容: " + safeName);
        }

        writeToStores(chunks, safeName, filePath);

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

    /**
     * 统一把逻辑 chunkId 和 Document.id 对齐，确保向量库/ES/前端引用使用同一主键。
     */
    private List<Document> normalizeChunkIds(List<Document> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return chunks;
        }
        return chunks.stream().map(doc -> {
            Map<String, Object> metadata = doc.getMetadata() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(doc.getMetadata());
            String chunkId = resolveChunkId(doc, metadata);
            metadata.put(ChunkMetadataKeys.CHUNK_ID, chunkId);
            return new Document(chunkId, doc.getText(), metadata);
        }).toList();
    }

    private String resolveChunkId(Document doc, Map<String, Object> metadata) {
        Object existingChunkId = metadata.get(ChunkMetadataKeys.CHUNK_ID);
        if (existingChunkId != null && StringUtils.hasText(existingChunkId.toString())) {
            return existingChunkId.toString();
        }
        if (doc != null && StringUtils.hasText(doc.getId())) {
            return doc.getId();
        }
        return UUID.randomUUID().toString();
    }
}
